package com.tang.intellij.lua.debug.protocol

/**
 * 协议 v3 的帧：`magic(2) | type(1) | length(4, 大端) | payload`。
 *
 * 用长度前缀代替旧协议的“命令号一行 + JSON 一行”：接收方按长度一次读完，不用扫描换行，
 * 大 payload（变量、栈）也不会因为内容里有换行而错位。
 */
enum class FrameType(val code: Int) {
    REQUEST(1),
    RESPONSE(2),
    EVENT(3);

    companion object {
        fun fromCode(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

class Frame(val type: FrameType, val payload: ByteArray, val cbor: Boolean = false) {
    override fun toString(): String = "Frame($type, ${payload.size} bytes${if (cbor) ", cbor" else ""})"
}

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

object FrameCodec {
    const val MAGIC_0: Int = 0xE3
    const val MAGIC_1: Int = 0x01
    const val HEADER_SIZE: Int = 7

    /** type 字节的 0x10 位：该帧 payload 是 CBOR。每帧自描述，不做中途切换。 */
    const val CBOR_FLAG: Int = 0x10

    /** 单帧上限 16 MiB；超过说明对端出错或不是 v3 协议，直接断开比尝试分配内存安全。 */
    const val MAX_PAYLOAD: Int = 16 * 1024 * 1024

    fun encode(frame: Frame): ByteArray {
        val size = frame.payload.size
        if (size == 0) throw ProtocolException("payload 不能为空")
        if (size > MAX_PAYLOAD) throw ProtocolException("帧太大：$size 字节，上限 $MAX_PAYLOAD")
        val out = ByteArray(HEADER_SIZE + size)
        out[0] = MAGIC_0.toByte()
        out[1] = MAGIC_1.toByte()
        out[2] = (frame.type.code or if (frame.cbor) CBOR_FLAG else 0).toByte()
        out[3] = (size ushr 24).toByte()
        out[4] = (size ushr 16).toByte()
        out[5] = (size ushr 8).toByte()
        out[6] = size.toByte()
        System.arraycopy(frame.payload, 0, out, HEADER_SIZE, size)
        return out
    }
}

/**
 * 流式解帧：网络读到多少喂多少，凑够一整帧就吐出来。
 * 不是线程安全的，一个连接一个实例，在读线程里用。
 */
class FrameDecoder(private val maxPayload: Int = FrameCodec.MAX_PAYLOAD) {
    private val header = ByteArray(FrameCodec.HEADER_SIZE)
    private var headerFilled = 0
    private var payload: ByteArray? = null
    private var payloadFilled = 0
    private var type: FrameType = FrameType.REQUEST
    private var cbor = false

    /** 喂入字节，返回本次凑齐的帧；格式错误抛 [ProtocolException]，调用方应断开连接。 */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<Frame> {
        var frames: MutableList<Frame>? = null
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val body = payload
            if (body == null) {
                val take = minOf(FrameCodec.HEADER_SIZE - headerFilled, end - pos)
                System.arraycopy(bytes, pos, header, headerFilled, take)
                headerFilled += take
                pos += take
                if (headerFilled < FrameCodec.HEADER_SIZE) break
                parseHeader()
            } else {
                val take = minOf(body.size - payloadFilled, end - pos)
                System.arraycopy(bytes, pos, body, payloadFilled, take)
                payloadFilled += take
                pos += take
                if (payloadFilled == body.size) {
                    frames = (frames ?: ArrayList()).also { it.add(finish()) }
                }
            }
        }
        return frames ?: emptyList()
    }

    private fun parseHeader() {
        val m0 = header[0].toInt() and 0xFF
        val m1 = header[1].toInt() and 0xFF
        if (m0 != FrameCodec.MAGIC_0 || m1 != FrameCodec.MAGIC_1) {
            throw ProtocolException(
                "不是 v3 调试协议的数据（magic=%02X %02X）：目标进程里的调试 runtime 版本过旧，请升级到 3.x。".format(m0, m1)
            )
        }
        val typeByte = header[2].toInt() and 0xFF
        cbor = typeByte and FrameCodec.CBOR_FLAG != 0
        // 低 4 位是消息种类，0x10 是 CBOR 位，其余位必须为 0
        if (typeByte and 0xE0 != 0) throw ProtocolException("帧类型字节的保留位不为 0：$typeByte")
        type = FrameType.fromCode(typeByte and 0x0F)
            ?: throw ProtocolException("未知帧类型 $typeByte")
        val size = ((header[3].toInt() and 0xFF) shl 24) or
            ((header[4].toInt() and 0xFF) shl 16) or
            ((header[5].toInt() and 0xFF) shl 8) or
            (header[6].toInt() and 0xFF)
        if (size == 0) throw ProtocolException("帧长度为 0（协议规定 payload 不能为空）")
        if (size < 0 || size > maxPayload) throw ProtocolException("帧长度 ${size.toUInt()} 超出上限 $maxPayload")
        payload = ByteArray(size)
        payloadFilled = 0
    }

    private fun finish(): Frame {
        val frame = Frame(type, payload!!, cbor)
        payload = null
        headerFilled = 0
        payloadFilled = 0
        return frame
    }
}

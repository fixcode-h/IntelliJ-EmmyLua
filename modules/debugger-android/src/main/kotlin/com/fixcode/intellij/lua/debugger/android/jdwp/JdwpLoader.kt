package com.fixcode.intellij.lua.debugger.android.jdwp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class JdwpException(message: String) : IOException(message)

/**
 * Android 8 及以下没有 attach-agent，用 JDWP 让应用自己 System.load 我们的 agent：
 * 这和 Android Studio 调试可调试应用用的是同一个系统接口，不需要 root，也不改包。
 *
 * 流程：握手 → 在 android.os.Handler.dispatchMessage 下一次性断点 → 主线程处理任何消息时停下 →
 * 在这个线程上 InvokeMethod System.load(path) → 清断点、恢复线程、断开。
 * UE 游戏主线程每帧都有 Choreographer 消息，通常几十毫秒内就会命中。
 *
 * 只实现这件事需要的最少命令，不是通用调试器。
 */
class JdwpLoader(private val host: String, private val port: Int, private val log: (String) -> Unit = {}) {

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var nextId = 1
    private var objectIdSize = 8
    private var methodIdSize = 8
    private var referenceTypeIdSize = 8
    private val pendingEvents = ArrayDeque<Packet>()

    /** 让目标进程加载 [libraryPath]（设备上的绝对路径，必须在应用自己能读的目录里）。 */
    fun loadLibrary(libraryPath: String, hitTimeoutMs: Int = 15_000) {
        connect()
        try {
            idSizes()
            val handler = classBySignature("Landroid/os/Handler;")
                ?: throw JdwpException("目标进程里找不到 android.os.Handler")
            val dispatch = method(handler, "dispatchMessage", "(Landroid/os/Message;)V")
                ?: throw JdwpException("找不到 Handler.dispatchMessage")
            val system = classBySignature("Ljava/lang/System;") ?: throw JdwpException("找不到 java.lang.System")
            val load = method(system, "load", "(Ljava/lang/String;)V") ?: throw JdwpException("找不到 System.load")

            val requestId = setBreakpoint(handler, dispatch)
            log("JDWP 断点已设置，等待主线程处理消息…")
            val thread = waitBreakpoint(requestId, hitTimeoutMs)
            try {
                clearBreakpoint(requestId)
                val path = createString(libraryPath)
                invokeStatic(system, thread, load, path)
                log("System.load 已执行")
            } finally {
                resumeThread(thread)
            }
        } finally {
            try {
                command(1, 6, ByteArray(0)) // VirtualMachine.Dispose：断开后应用照常运行
            } catch (_: IOException) {
            }
            socket.close()
        }
    }

    private fun connect() {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.soTimeout = 10_000
        input = DataInputStream(socket.getInputStream().buffered())
        output = DataOutputStream(socket.getOutputStream())
        val hs = "JDWP-Handshake".toByteArray(Charsets.US_ASCII)
        output.write(hs)
        output.flush()
        val reply = ByteArray(hs.size)
        try {
            input.readFully(reply)
        } catch (e: IOException) {
            throw JdwpException("JDWP 握手失败：应用可能不可调试，或已有调试器连着（Android Studio 断开后再试）")
        }
        if (!reply.contentEquals(hs)) throw JdwpException("JDWP 握手失败：对端返回了意外数据")
    }

    private fun idSizes() {
        val r = command(1, 7, ByteArray(0))
        val fieldIdSize = r.int()
        methodIdSize = r.int()
        objectIdSize = r.int()
        referenceTypeIdSize = r.int()
        if (fieldIdSize !in 1..8 || methodIdSize !in 1..8 || objectIdSize !in 1..8 || referenceTypeIdSize !in 1..8) {
            throw JdwpException("JDWP IDSizes 异常")
        }
    }

    private fun classBySignature(signature: String): Long? {
        val r = command(1, 2, Body().string(signature).bytes())
        val count = r.int()
        if (count == 0) return null
        r.byte() // refTypeTag
        val id = r.id(referenceTypeIdSize)
        return id
    }

    private fun method(type: Long, name: String, signature: String): Long? {
        val r = command(2, 5, Body().id(type, referenceTypeIdSize).bytes())
        val count = r.int()
        repeat(count) {
            val id = r.id(methodIdSize)
            val n = r.string()
            val sig = r.string()
            r.int() // modBits
            if (n == name && sig == signature) return id
        }
        return null
    }

    private fun setBreakpoint(type: Long, method: Long): Int {
        val body = Body()
            .byte(2) // eventKind BREAKPOINT
            .byte(1) // suspendPolicy EVENT_THREAD：只停命中的线程
            .int(2)
            .byte(1).int(1) // Count 修饰：只触发一次
            .byte(7) // LocationOnly
            .byte(1) // typeTag CLASS
            .id(type, referenceTypeIdSize)
            .id(method, methodIdSize)
            .long(0) // 方法入口
        return command(15, 1, body.bytes()).int()
    }

    private fun clearBreakpoint(requestId: Int) {
        command(15, 2, Body().byte(2).int(requestId).bytes())
    }

    private fun waitBreakpoint(requestId: Int, timeoutMs: Int): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) throw JdwpException("等了 ${timeoutMs / 1000} 秒主线程都没有处理消息：请把游戏切到前台，或在屏幕上点一下再试")
            val packet = pendingEvents.removeFirstOrNull() ?: readPacket(remain.toInt())
            if (!packet.isEvent) continue
            val r = Reader(packet.data)
            r.byte() // suspendPolicy
            val events = r.int()
            for (i in 0 until events) {
                val kind = r.byte().toInt()
                val id = r.int()
                when (kind) {
                    2 -> {
                        val thread = r.id(objectIdSize)
                        // location：typeTag + classID + methodID + index
                        r.byte(); r.id(referenceTypeIdSize); r.id(methodIdSize); r.long()
                        if (id == requestId) return thread
                    }
                    // VM_START / THREAD_START / THREAD_DEATH：只带一个线程 ID
                    90, 6, 7 -> r.id(objectIdSize)
                    // 其他事件我们没订阅，格式不同，不再往下解析这个包
                    else -> break
                }
            }
        }
    }

    private fun createString(value: String): Long =
        command(1, 11, Body().string(value).bytes()).id(objectIdSize)

    private fun invokeStatic(type: Long, thread: Long, method: Long, arg: Long) {
        val body = Body()
            .id(type, referenceTypeIdSize)
            .id(thread, objectIdSize)
            .id(method, methodIdSize)
            .int(1)
            .byte('L'.code).id(arg, objectIdSize)
            .int(1) // INVOKE_SINGLE_THREADED：调用期间其他线程保持原状态
        val r = command(3, 3, body.bytes(), timeoutMs = 30_000)
        r.taggedValue(objectIdSize) // 返回值（void）
        r.byte() // exception tag
        val exception = r.id(objectIdSize)
        if (exception != 0L) {
            throw JdwpException("System.load 抛出了异常：agent 所在目录应用读不到，或 so 的 ABI 和进程不一致")
        }
    }

    private fun resumeThread(thread: Long) {
        try {
            command(11, 3, Body().id(thread, objectIdSize).bytes())
        } catch (_: IOException) {
        }
    }

    // ---- 包读写 ----

    private class Packet(val id: Int, val flags: Int, val commandSet: Int, val command: Int, val errorCode: Int, val data: ByteArray) {
        val isReply: Boolean get() = flags and 0x80 != 0
        val isEvent: Boolean get() = !isReply && commandSet == 64 && command == 100
    }

    private fun command(set: Int, cmd: Int, body: ByteArray, timeoutMs: Int = 10_000): Reader {
        val id = nextId++
        output.writeInt(11 + body.size)
        output.writeInt(id)
        output.writeByte(0)
        output.writeByte(set)
        output.writeByte(cmd)
        output.write(body)
        output.flush()
        while (true) {
            val p = readPacket(timeoutMs)
            if (p.isReply && p.id == id) {
                if (p.errorCode != 0) throw JdwpException("JDWP 命令 $set/$cmd 失败，错误码 ${p.errorCode}")
                return Reader(p.data)
            }
            if (p.isEvent) pendingEvents.addLast(p)
        }
    }

    private fun readPacket(timeoutMs: Int): Packet {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        try {
            val length = input.readInt()
            if (length < 11 || length > 64 * 1024 * 1024) throw JdwpException("JDWP 包长度异常：$length")
            val id = input.readInt()
            val flags = input.readUnsignedByte()
            val a = input.readUnsignedByte()
            val b = input.readUnsignedByte()
            val data = ByteArray(length - 11)
            input.readFully(data)
            return if (flags and 0x80 != 0) Packet(id, flags, 0, 0, (a shl 8) or b, data)
            else Packet(id, flags, a, b, 0, data)
        } catch (e: SocketTimeoutException) {
            throw JdwpException("JDWP 等待超时")
        } catch (e: EOFException) {
            throw JdwpException("JDWP 连接被关闭")
        }
    }

    private class Body {
        private val buf = java.io.ByteArrayOutputStream()
        private val out = DataOutputStream(buf)
        fun byte(v: Int) = apply { out.writeByte(v) }
        fun int(v: Int) = apply { out.writeInt(v) }
        fun long(v: Long) = apply { out.writeLong(v) }
        fun id(v: Long, size: Int) = apply { for (i in size - 1 downTo 0) out.writeByte((v ushr (i * 8)).toInt() and 0xff) }
        fun string(s: String) = apply {
            val b = s.toByteArray(Charsets.UTF_8)
            out.writeInt(b.size)
            out.write(b)
        }
        fun bytes(): ByteArray = buf.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        fun byte(): Byte = data[pos++]
        fun int(): Int {
            var v = 0
            repeat(4) { v = (v shl 8) or (data[pos++].toInt() and 0xff) }
            return v
        }
        fun long(): Long = id(8)
        fun id(size: Int): Long {
            var v = 0L
            repeat(size) { v = (v shl 8) or (data[pos++].toLong() and 0xff) }
            return v
        }
        fun string(): String {
            val n = int()
            val s = String(data, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }
        fun taggedValue(objectIdSize: Int) {
            when (byte().toInt().toChar()) {
                'V' -> {}
                'B', 'Z' -> pos += 1
                'C', 'S' -> pos += 2
                'I', 'F' -> pos += 4
                'J', 'D' -> pos += 8
                else -> pos += objectIdSize
            }
        }
    }
}

package com.tang.intellij.lua.debug.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrameCodecTest {
    @Test
    fun `帧头是 magic + type + 大端长度`() {
        val bytes = FrameCodec.encode(Frame(FrameType.EVENT, byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(0xE3.toByte(), 0x01, 0x03, 0, 0, 0, 3, 1, 2, 3), bytes)
        val cbor = FrameCodec.encode(Frame(FrameType.REQUEST, ByteArray(0x0102), cbor = true))
        assertEquals(0x11, cbor[2].toInt())
        assertEquals(0x01, cbor[5].toInt())
        assertEquals(0x02, cbor[6].toInt())
    }

    @Test
    fun `逐字节喂入也能拼出完整帧`() {
        val a = FrameCodec.encode(Frame(FrameType.REQUEST, "{\"x\":1}".toByteArray()))
        val b = FrameCodec.encode(Frame(FrameType.RESPONSE, "{\"y\":2}".toByteArray()))
        val stream = a + b
        val decoder = FrameDecoder()
        val frames = stream.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(listOf(FrameType.REQUEST, FrameType.RESPONSE), frames.map { it.type })
        assertEquals("{\"y\":2}", String(frames[1].payload))
    }

    @Test
    fun `一次喂入多帧全部吐出`() {
        val stream = (1..5).map { FrameCodec.encode(Frame(FrameType.EVENT, "{\"seq\":$it}".toByteArray())) }
            .reduce { acc, bytes -> acc + bytes }
        assertEquals(5, FrameDecoder().feed(stream).size)
    }

    @Test
    fun `旧版 runtime 的文本行被识别为协议过旧`() {
        val legacy = "0\n{\"cmd\":0}\n".toByteArray()
        try {
            FrameDecoder().feed(legacy)
            fail("应当抛出 ProtocolException")
        } catch (error: ProtocolException) {
            assertTrue(error.message!!.contains("版本过旧"))
        }
    }

    @Test
    fun `超长帧和空帧和保留位都直接拒绝`() {
        val tooLarge = byteArrayOf(0xE3.toByte(), 0x01, 0x01, 0x7F, 0, 0, 0)
        val empty = byteArrayOf(0xE3.toByte(), 0x01, 0x01, 0, 0, 0, 0)
        val reserved = byteArrayOf(0xE3.toByte(), 0x01, 0x41, 0, 0, 0, 1)
        for (bytes in listOf(tooLarge, empty, reserved)) {
            try {
                FrameDecoder().feed(bytes)
                fail("应当抛出 ProtocolException：${bytes.joinToString()}")
            } catch (expected: ProtocolException) {
            }
        }
    }
}

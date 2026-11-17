package com.fixcode.intellij.lua.debug.xdebugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocalAttachToolTest {
    @Test
    fun `取最后一行 JSON 作为结果 前面的日志忽略`() {
        val output = "inject start\nwaiting for hook\n{\"ok\":true,\"protocol\":3,\"host\":\"127.0.0.1\",\"port\":40211,\"pid\":1234,\"reinjected\":true}\n"
        val result = LocalAttachTool.parseResult(output, 0)
        assertEquals(LocalAttachTool.Result("127.0.0.1", 40211, 1234, true), result)
    }

    @Test
    fun `失败时把中文原因带给用户`() {
        val error = runCatching { LocalAttachTool.parseResult("{\"ok\":false,\"error\":\"目标进程没有加载 Lua\"}", 2) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message, error.message!!.contains("目标进程没有加载 Lua"))
    }

    @Test
    fun `旧版调试组件明确提示重启游戏`() {
        val error = runCatching { LocalAttachTool.parseResult("{\"ok\":true,\"protocol\":2,\"port\":1}", 0) }.exceptionOrNull()
        assertTrue(error!!.message, error.message!!.contains("重启游戏"))
    }

    @Test
    fun `没有 JSON 时给出输出尾部`() {
        val error = runCatching { LocalAttachTool.parseResult("crash: access denied", 5) }.exceptionOrNull()
        assertTrue(error!!.message, error.message!!.contains("access denied") && error.message!!.contains("5"))
    }

    @Test
    fun `token 是 32 位十六进制且每次不同`() {
        val a = LocalAttachTool.newToken()
        assertTrue(a.matches(Regex("[0-9a-f]{32}")))
        assertTrue(a != LocalAttachTool.newToken())
    }

    @Test
    fun `还没加载 Lua 时返回 waiting`() {
        val r = LocalAttachTool.parseResult(
            """{"ok":true,"protocol":3,"host":"127.0.0.1","port":9966,"pid":7,"reinjected":false,"waiting":true,"message":"目标进程还没有加载 Lua"}""", 0
        )
        org.junit.Assert.assertTrue(r.waiting)
        org.junit.Assert.assertEquals("目标进程还没有加载 Lua", r.message)
        org.junit.Assert.assertEquals(9966, r.port)
    }

    private fun fakeExe(machine: Int): java.io.File {
        val bytes = ByteArray(0x100)
        bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
        bytes[0x3C] = 0x80.toByte()
        bytes[0x80] = 'P'.code.toByte(); bytes[0x81] = 'E'.code.toByte()
        bytes[0x84] = (machine and 0xFF).toByte(); bytes[0x85] = (machine shr 8).toByte()
        return java.io.File.createTempFile("fake", ".exe").apply { deleteOnExit(); writeBytes(bytes) }
    }

    @Test
    fun `启动并附加按 PE 头选位数`() {
        assertEquals("x64", LocalAttachTool.exeArch(fakeExe(0x8664)))
        assertEquals("x86", LocalAttachTool.exeArch(fakeExe(0x014C)))
        val arm = runCatching { LocalAttachTool.exeArch(fakeExe(0xAA64)) }.exceptionOrNull()
        assertTrue(arm is IOException && arm.message!!.contains("不支持"))
        val text = java.io.File.createTempFile("notexe", ".exe").apply { deleteOnExit(); writeText("hello") }
        assertTrue(runCatching { LocalAttachTool.exeArch(text) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `启动结果带 launched 和新进程号`() {
        val r = LocalAttachTool.parseResult("{\"ok\":true,\"protocol\":3,\"host\":\"127.0.0.1\",\"port\":9001,\"pid\":4321,\"reinjected\":false,\"waiting\":true,\"message\":\"等 Lua\",\"launched\":true}", 0)
        assertEquals(4321, r.pid)
        assertTrue(r.waiting)
    }
}

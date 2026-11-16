package com.tang.intellij.lua.debug.xdebugger

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
}

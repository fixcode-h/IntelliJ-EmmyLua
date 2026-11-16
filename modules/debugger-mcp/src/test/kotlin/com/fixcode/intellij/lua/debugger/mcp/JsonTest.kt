package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.json.Json
import com.fixcode.intellij.lua.debugger.mcp.json.JsonArgs
import com.fixcode.intellij.lua.debugger.mcp.json.ToolArgumentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test
    fun `往返保留键顺序和类型`() {
        val text = """{"b":1,"a":[true,null,2.5,"x\n中"],"c":{}}"""
        val v = Json.parse(text)
        assertEquals(text.replace("\\u4e2d", "中"), Json.write(v))
    }

    @Test
    fun `缩进输出和缩进检测`() {
        val pretty = Json.pretty(mapOf("a" to mapOf("b" to 1)), "\t")
        assertEquals("{\n\t\"a\": {\n\t\t\"b\": 1\n\t}\n}", pretty)
        assertEquals("\t", Json.detectIndent(pretty))
        assertEquals("  ", Json.detectIndent("{}"))
    }

    @Test
    fun `拒绝注释和尾逗号`() {
        assertThrows(Json.ParseException::class.java) { Json.parse("{// x\n}") }
        assertThrows(Json.ParseException::class.java) { Json.parse("[1,]") }
        assertThrows(Json.ParseException::class.java) { Json.parse("{} x") }
    }

    @Test
    fun `参数校验`() {
        val args = JsonArgs(mapOf("limit" to "20", "kind" to "INTO"), setOf("limit", "kind", "file"))
        assertEquals(20, args.int("limit", 30, 1, 200))
        assertEquals("into", args.choice("kind", "over", "over", "into", "out"))
        assertThrows(ToolArgumentException::class.java) { JsonArgs(mapOf("lmit" to 1), setOf("limit")) }
        assertThrows(ToolArgumentException::class.java) { JsonArgs(mapOf("limit" to 500)).int("limit", 30, 1, 200) }
        assertEquals(listOf("a", "b"), JsonArgs(mapOf("x" to "a, b")).stringList("x"))
    }
}

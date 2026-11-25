package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.json.Jsonc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsoncTest {
    private val entry = linkedMapOf("url" to "u")

    @Test
    fun `认注释和尾逗号，字符串里的斜杠不算注释`() {
        val obj = Jsonc.parseObject("{ /* a */ \"u\": \"http://x//y\", // b\n \"n\": [1, 2,], }")
        assertEquals("http://x//y", obj["u"])
        assertTrue(Jsonc.hasExtensions("{\"a\": 1,}"))
        assertFalse(Jsonc.hasExtensions("{\"a\": \"//not\"}"))
    }

    @Test
    fun `加在最后一项后面，行尾注释留在原来那一项`() {
        val text = "{\n  \"s\": {\n    \"a\": 1 // keep\n  }\n}\n"
        assertEquals(
            "{\n  \"s\": {\n    \"a\": 1, // keep\n    \"x\": {\n      \"url\": \"u\"\n    }\n  }\n}\n",
            Jsonc.setMember(text, listOf("s"), "x", entry, "  "),
        )
    }

    @Test
    fun `已有的只换值`() {
        val text = "{\n  // c\n  \"s\": { \"x\": 1, \"b\": 2 }\n}"
        assertEquals("{\n  // c\n  \"s\": { \"x\": 3, \"b\": 2 }\n}", Jsonc.setMember(text, listOf("s"), "x", 3, "  "))
    }

    @Test
    fun `空对象和缺的容器`() {
        assertEquals(
            "{\n  // c\n  \"s\": {\n    \"x\": 1\n  }\n}",
            Jsonc.setMember("{\n  // c\n  \"s\": {}\n}", listOf("s"), "x", 1, "  "),
        )
        assertEquals(
            "{\n  \"t\": true, // c\n  \"s\": {\n    \"x\": 1\n  }\n}",
            Jsonc.setMember("{\n  \"t\": true // c\n}", listOf("s"), "x", 1, "  "),
        )
        assertEquals("{\n  \"s\": {\n    \"x\": 1\n  }\n}", Jsonc.setMember("{}", listOf("s"), "x", 1, "  "))
    }

    @Test
    fun `删一项不留逗号和空行`() {
        val text = "{\n  \"s\": {\n    \"a\": 1, // a\n    \"x\": 2,\n    \"b\": 3\n  }\n}"
        assertEquals("{\n  \"s\": {\n    \"a\": 1, // a\n    \"b\": 3\n  }\n}", Jsonc.removeMember(text, listOf("s"), "x"))
        assertEquals("{\n  \"s\": {\n    \"a\": 1, // a\n    \"x\": 2\n  }\n}", Jsonc.removeMember(text, listOf("s"), "b"))
        assertEquals(text, Jsonc.removeMember(text, listOf("s"), "zz"))
        assertEquals("{ \"s\": {} }", Jsonc.removeMember("{ \"s\": {\"x\": 1} }", listOf("s"), "x"))
    }
}

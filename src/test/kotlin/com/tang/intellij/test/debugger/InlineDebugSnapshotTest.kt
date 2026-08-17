package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.debugPathsEqual
import com.tang.intellij.lua.debugger.emmy.LuaValueType
import com.tang.intellij.lua.debugger.emmy.Stack
import com.tang.intellij.lua.debugger.emmy.VariableValue
import com.tang.intellij.lua.debugger.luapanda.LuaPandaStack
import com.tang.intellij.lua.debugger.luapanda.LuaPandaVariable
import com.tang.intellij.lua.debugger.toInlineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineDebugSnapshotTest {
    @Test
    fun `path identity normalizes separators and case policy`() {
        assertTrue(debugPathsEqual("C:\\Game\\main.lua", "C:/Game/main.lua/", caseSensitive = true))
        assertTrue(debugPathsEqual("C:/GAME/main.lua", "c:/game/MAIN.lua", caseSensitive = false))
        assertFalse(debugPathsEqual("C:/GAME/main.lua", "c:/game/MAIN.lua", caseSensitive = true))
    }

    @Test
    fun `Emmy snapshot recursively maps variables and locals shadow upvalues`() {
        val upvalue = emmyValue("value", "upvalue")
        val local = emmyValue(
            "value",
            "table: 1",
            LuaValueType.TTABLE,
            children = listOf(emmyValue("child", "42", LuaValueType.TNUMBER))
        )
        val snapshot = Stack("wire-path.lua", 8, "main", 0, listOf(local), listOf(upvalue))
            .toInlineSnapshot("C:/Game/main.lua", 7)

        assertEquals("C:/Game/main.lua", snapshot.filePath)
        assertEquals(7, snapshot.line)
        assertEquals("table: 1", snapshot.variables.getValue("value").value)
        assertEquals("42", snapshot.variables.getValue("value").children.getValue("child").value)
    }

    @Test
    fun `LuaPanda snapshot recursively maps variables and locals shadow upvalues`() {
        val snapshot = LuaPandaStack(
            file = "main.lua",
            line = "9",
            name = "main",
            index = "0",
            locals = listOf(LuaPandaVariable("self", "table", "table", children = listOf(
                LuaPandaVariable("enabled", "true", "boolean")
            ))),
            upvalues = listOf(LuaPandaVariable("self", "old", "string"))
        ).toInlineSnapshot("C:/Game/main.lua", 8)

        val self = snapshot.variables.getValue("self")
        assertEquals("table", self.type)
        assertEquals("true", self.children.getValue("enabled").value)
    }

    private fun emmyValue(
        name: String,
        value: String,
        type: LuaValueType = LuaValueType.TSTRING,
        children: List<VariableValue>? = null
    ) = VariableValue(
        name = name,
        nameType = LuaValueType.TSTRING.wireId,
        value = value,
        valueType = type.wireId,
        valueTypeName = type.name,
        cacheId = 0,
        children = children
    )
}

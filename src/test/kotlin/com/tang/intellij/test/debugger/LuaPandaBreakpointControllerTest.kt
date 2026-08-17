package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.luapanda.LuaPandaBreakpointController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaPandaBreakpointControllerTest {
    @Test
    fun `protocol snapshots always contain the complete file set`() {
        val controller = LuaPandaBreakpointController()
        controller.upsert(1, "C:\\game\\main.lua", 10)

        val added = controller.upsert(2, "C:/game/main.lua", 20).single()
        val removed = controller.remove(1)!!

        assertEquals(listOf(10, 20), added.bks.map { it.line })
        assertEquals(listOf(20), removed.bks.map { it.line })
        assertTrue(controller.remove(2)!!.bks.isEmpty())
    }

    @Test
    fun `temporary snapshot preserves persistent breakpoints`() {
        val controller = LuaPandaBreakpointController()
        controller.upsert(1, "/game/main.lua", 10)

        val temporary = controller.snapshotWithTemporary("/game/main.lua", 15)

        assertEquals(listOf(10, 15), temporary.bks.map { it.line })
        assertEquals(listOf(10), controller.persistentSnapshot("/game/main.lua").bks.map { it.line })
    }
}

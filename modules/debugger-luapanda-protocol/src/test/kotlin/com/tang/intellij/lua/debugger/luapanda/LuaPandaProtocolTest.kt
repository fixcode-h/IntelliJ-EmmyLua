package com.tang.intellij.lua.debugger.luapanda

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaPandaProtocolTest {
    @Test
    fun `message round trip preserves stack contract`() {
        val message = LuaPandaMessage(
            cmd = LuaPandaCommands.STOP_ON_BREAKPOINT,
            info = JsonObject(),
            callbackId = "7",
            stack = listOf(LuaPandaStack("main.lua", "12", "main", "0"))
        )

        val restored = Gson().fromJson(Gson().toJson(message), LuaPandaMessage::class.java)
        assertEquals("7", restored.callbackId)
        assertEquals(12, restored.stack?.single()?.getLineNumber())
        assertTrue(restored.getInfoAsObject()?.isJsonObject == true)
    }

    @Test
    fun `command strings match LuaPanda protocol`() {
        assertEquals("setBreakPoint", LuaPandaCommands.SET_BREAKPOINT)
        assertEquals("stopOnStep", LuaPandaCommands.STEP_OVER)
        assertEquals("stopOnStepIn", LuaPandaCommands.STEP_IN)
        assertEquals("stopOnStepOut", LuaPandaCommands.STEP_OUT)
        assertEquals("stopRun", LuaPandaCommands.STOP_RUN)
    }
}

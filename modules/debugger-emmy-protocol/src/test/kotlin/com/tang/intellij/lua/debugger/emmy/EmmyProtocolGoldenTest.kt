package com.tang.intellij.lua.debugger.emmy

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EmmyProtocolGoldenTest {
    @Test
    fun `message command wire ids remain stable`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17),
            MessageCMD.entries.map(MessageCMD::wireId)
        )
        MessageCMD.entries.forEach { assertEquals(it, MessageCMD.fromWireId(it.wireId)) }
        assertEquals(MessageCMD.Unknown, MessageCMD.fromWireId(999))
    }

    @Test
    fun `debug action and value type wire ids remain stable`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), DebugAction.entries.map(DebugAction::wireId))
        assertEquals((0..9).toList(), LuaValueType.entries.map(LuaValueType::wireId))
        LuaValueType.entries.forEach { assertEquals(it, LuaValueType.fromWireId(it.wireId)) }
    }

    @Test
    fun `serialized messages use explicit wire ids`() {
        val json = JsonParser.parseString(DebugActionMessage(DebugAction.StepOut).toJSON()).asJsonObject
        assertEquals(9, json.get("cmd").asInt)
        assertEquals(4, json.get("action").asInt)
        assertEquals(11, EvalReq("value", 0, 0, 1).cmd)
    }

    @Test
    fun `request sequences are unique across threads`() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val sequences = ConcurrentHashMap.newKeySet<Int>()
            val futures = (1..1_000).map {
                executor.submit { sequences += Message.makeSeq() }
            }
            futures.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1_000, sequences.size)
        } finally {
            executor.shutdownNow()
        }
    }
}

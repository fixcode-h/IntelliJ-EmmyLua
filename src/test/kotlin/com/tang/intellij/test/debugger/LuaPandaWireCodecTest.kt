package com.tang.intellij.test.debugger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tang.intellij.lua.debugger.luapanda.LuaPandaMessage
import com.tang.intellij.lua.debugger.luapanda.LuaPandaWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaPandaWireCodecTest {
    private val codec = LuaPandaWireCodec()

    @Test
    fun `message round trip preserves fields and wire separator`() {
        val info = JsonObject().apply { addProperty("expression", "player.hp") }
        val encoded = codec.encode(LuaPandaMessage("getWatchedVariable", info, "callback-7"))

        assertTrue(encoded.payload.endsWith(" ${LuaPandaWireCodec.PROTOCOL_SEPARATOR}\n"))
        assertEquals(encoded.json, codec.extractJson(encoded.payload))

        val decoded = codec.decode(encoded.payload)
        assertEquals("getWatchedVariable", decoded.cmd)
        assertEquals("callback-7", decoded.callbackId)
        assertEquals("player.hp", decoded.getInfoAsObject()!!.get("expression").asString)
    }

    @Test
    fun `command encoding omits absent info`() {
        val encoded = codec.encodeCommand("continue", "callback-8", null)
        val json = JsonParser.parseString(encoded.json).asJsonObject

        assertEquals("continue", json.get("cmd").asString)
        assertEquals("callback-8", json.get("callbackId").asString)
        assertFalse(json.has("info"))
        assertEquals(encoded.json, codec.extractJson(encoded.json))
    }
}

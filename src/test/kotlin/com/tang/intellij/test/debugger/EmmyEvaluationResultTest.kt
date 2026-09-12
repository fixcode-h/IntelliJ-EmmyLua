package com.tang.intellij.test.debugger

import com.google.gson.JsonParser
import com.tang.intellij.lua.debugger.cli.EmmyEvaluationResult
import com.tang.intellij.lua.debugger.emmy.EmmyV2Envelope
import org.junit.Assert.*
import org.junit.Test

class EmmyEvaluationResultTest {
    @Test fun `table capture retains values for AI instead of just table display`() {
        val payload = JsonParser.parseString("""{"expr":"self","success":true,"value":{"valueTypeName":"table","value":"table","children":[{"name":"health","valueTypeName":"number","value":"42"},{"name":"name","valueTypeName":"string","value":"中文\u0000尾"}]}}""").asJsonObject
        val value = EmmyEvaluationResult.decode(EmmyV2Envelope(kind = "response", type = "debug.eval", ok = true, payload = payload)).getOrThrow()
        assertEquals("42", value.children[0].display)
        assertEquals("中文\u0000尾", value.children[1].display)
        assertNull(value.children[0].variablesReference)
    }

    @Test fun `native evaluation error code survives response decoding`() {
        val payload = JsonParser.parseString("""{"success":false,"error":"STALE_PAUSE_REFERENCE"}""").asJsonObject
        val result = EmmyEvaluationResult.decode(EmmyV2Envelope(kind = "response", type = "debug.eval", ok = false, payload = payload))
        assertEquals("STALE_PAUSE_REFERENCE", result.exceptionOrNull()?.message)
    }

    @Test fun `oversized native result is rejected before forwarding`() {
        val payload = JsonParser.parseString("""{"value":{"value":"${"x".repeat(65537)}"}}""").asJsonObject
        val result = EmmyEvaluationResult.decode(EmmyV2Envelope(kind = "response", type = "debug.eval", ok = true, payload = payload))
        assertEquals("EVALUATION_LIMIT_EXCEEDED", result.exceptionOrNull()?.message)
    }
}

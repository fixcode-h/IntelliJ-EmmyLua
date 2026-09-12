package com.tang.intellij.lua.debugger.cli

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliProtocolTest {
    @Test
    fun `request round trips unknown arguments`() {
        val request = CliJsonLines.decodeRequest(
            "{\"requestId\":\"r1\",\"operation\":\"vm.list\",\"future\":true,\"arguments\":{\"limit\":10}}"
        )
        assertEquals("r1", request.requestId)
        assertEquals("vm.list", request.operation)
        assertEquals(10, request.arguments["limit"].asInt)
    }

    @Test
    fun `responses stay single line and preserve structured errors`() {
        val response = CliResponse(
            requestId = "r2",
            ok = false,
            error = CliError("TARGET_BUSY", "another client owns the lease", retryable = true)
        )
        val encoded = CliJsonLines.encode(response)
        assertFalse(encoded.contains('\n'))
        val json = JsonParser.parseString(encoded).asJsonObject
        assertEquals("TARGET_BUSY", json.getAsJsonObject("error").get("code").asString)
        assertTrue(json.getAsJsonObject("error").get("retryable").asBoolean)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized request is rejected before dispatch`() {
        CliJsonLines.decodeRequest(
            "{\"requestId\":\"r\",\"operation\":\"vm.list\",\"arguments\":{\"x\":\"${"x".repeat(CLI_MAX_LINE_BYTES)}\"}}"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed request id is rejected`() {
        CliJsonLines.decodeRequest("{\"requestId\":\"\",\"operation\":\"vm.list\"}")
    }

    @Test
    fun `instance descriptor and VM summary use stable schema fields`() {
        val descriptor = CliInstanceDescriptor(
            ideaInstanceId = "idea-1",
            pid = 42,
            product = "Rider",
            endpoint = "tcp://127.0.0.1:1234",
            startedAt = "2026-09-09T10:00:00Z",
            tokenFile = "token"
        )
        val vm = CliVmSummary("vm-1", 2, "PIE", "PAUSED", "5.4.3", "HOST_API", 7)
        assertEquals(1, descriptor.schemaVersion)
        assertEquals("PAUSED", vm.state)
        assertEquals(7L, vm.activePauseId)
    }
}

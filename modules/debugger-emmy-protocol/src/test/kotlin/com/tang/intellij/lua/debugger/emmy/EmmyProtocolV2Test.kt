package com.tang.intellij.lua.debugger.emmy

import com.google.gson.JsonParser
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmmyProtocolV2Test {
    @Test
    fun `legacy break notification carries optional VM pause identity`() {
        val json = JsonParser.parseString(
            Gson().toJson(BreakNotify(emptyList(), vmId = "vm-7", pauseId = 12))
        ).asJsonObject

        assertEquals("vm-7", json.get("vmId").asString)
        assertEquals(12L, json.get("pauseId").asLong)
    }

    @Test
    fun `handshake and lifecycle envelopes preserve golden fields`() {
        val init = EmmyV2Envelope(
            kind = "response",
            type = "agent.describe",
            agentSessionId = "agent-1",
            connectionEpoch = 2,
            payload = JsonParser.parseString(
                "{\"protocolVersion\":2,\"processId\":42,\"capabilities\":[\"vm.snapshot\"]}"
            ).asJsonObject
        )
        val ready = EmmyV2Envelope(
            kind = "response",
            type = "agent.ready",
            agentSessionId = "agent-1",
            connectionEpoch = 2,
            payload = JsonParser.parseString("{\"snapshotEventSeq\":3}").asJsonObject
        )
        val lifecycle = EmmyV2Envelope(
            kind = "event",
            type = "vm.lifecycle",
            agentSessionId = "agent-1",
            connectionEpoch = 2,
            eventSeq = 4,
            target = EmmyV2Target(vmId = "vm-7"),
            payload = JsonParser.parseString("{\"current\":\"READY\"}").asJsonObject
        )

        val initJson = JsonParser.parseString(init.toJson()).asJsonObject
        val readyJson = JsonParser.parseString(ready.toJson()).asJsonObject
        val lifecycleJson = JsonParser.parseString(lifecycle.toJson()).asJsonObject

        assertEquals("agent.describe", initJson.get("type").asString)
        assertEquals(2L, initJson.get("connectionEpoch").asLong)
        assertEquals(3L, readyJson.getAsJsonObject("payload").get("snapshotEventSeq").asLong)
        assertEquals("vm-7", lifecycleJson.getAsJsonObject("target").get("vmId").asString)
        assertEquals(4L, lifecycleJson.get("eventSeq").asLong)
    }

    @Test
    fun `v2 envelope uses reserved wire id and omits absent optional fields`() {
        val envelope = EmmyV2Envelope(
            kind = "event",
            type = "vm.lifecycle",
            eventSeq = 7,
            target = EmmyV2Target(vmId = "vm-1")
        )

        val json = JsonParser.parseString(envelope.toJson()).asJsonObject

        assertEquals(MessageCMD.EnvelopeV2.wireId, json.get("cmd").asInt)
        assertEquals(MessageCMD.EnvelopeV2, MessageCMD.fromWireId(json.get("cmd").asInt))
        assertEquals(2, json.get("protocolVersion").asInt)
        assertEquals("event", json.get("kind").asString)
        assertEquals("vm.lifecycle", json.get("type").asString)
        assertEquals(7L, json.get("eventSeq").asLong)
        assertEquals("vm-1", json.getAsJsonObject("target").get("vmId").asString)
        assertFalse(json.has("requestId"))
        assertFalse(json.has("error"))
    }

    @Test
    fun `v2 envelope round trips target error and arbitrary payload`() {
        val original = EmmyV2Envelope(
            kind = "response",
            type = "vm.snapshot",
            requestId = "req-1",
            agentSessionId = "agent-1",
            connectionEpoch = 4,
            contextGeneration = 5,
            sourceEpoch = 6,
            eventSeq = 12,
            target = EmmyV2Target(
                vmId = "vm-1",
                threadId = "thread-1",
                pauseId = 9,
                frameId = "frame-0"
            ),
            ok = false,
            error = EmmyV2Error(
                code = "VM_NOT_READY",
                message = "Lua VM is not ready",
                retryable = true
            ),
            payload = JsonParser.parseString("{\"snapshotEventSeq\":12,\"vms\":[]}").asJsonObject
        )

        val decoded = EmmyV2Envelope.fromJson(original.toJson())

        assertEquals(original, decoded)
        assertEquals(5L, decoded.contextGeneration)
        assertEquals(6L, decoded.sourceEpoch)
        assertEquals(12L, decoded.payload?.get("snapshotEventSeq")?.asLong)
        assertEquals("VM_NOT_READY", decoded.error?.code)
        assertTrue(decoded.error?.retryable == true)
    }

    @Test
    fun `unknown fields are ignored when decoding v2 envelopes`() {
        val json = JsonParser.parseString(
            """
            {
              "cmd": 18,
              "protocolVersion": 2,
              "kind": "event",
              "type": "vm.lifecycle",
              "futureField": "ignored",
              "target": {"vmId": "vm-2", "futureTargetField": 3}
            }
            """.trimIndent()
        ).asJsonObject

        val decoded = EmmyV2Envelope.fromJson(json.toString())

        assertEquals("vm.lifecycle", decoded.type)
        assertEquals("vm-2", decoded.target?.vmId)
        assertNull(decoded.requestId)
        assertNull(decoded.error)
    }

    @Test
    fun `vm snapshot lifecycle and agent describe DTOs preserve their fields`() {
        val vm = VmDto(
            vmId = "vm-1",
            generation = 3,
            displayName = "PIE Lua",
            state = "READY",
            luaVersion = "5.4.3",
            discovery = "HOST_API",
            diagnosticStateAddress = "0x1234"
        )
        val snapshot = VmSnapshotDto(snapshotEventSeq = 10, vms = listOf(vm))
        val lifecycle = VmLifecycleDto(
            vmId = "vm-1",
            generation = 3,
            previous = "CREATED",
            current = "READY",
            reason = "host-ready",
            eventSeq = 10
        )
        val agent = AgentDescribeDto(
            agentSessionId = "agent-1",
            protocolVersion = 2,
            processId = 1234,
            capabilities = listOf("vm.lifecycle", "debug.pause")
        )

        val snapshotJson = JsonParser.parseString(snapshot.toJson()).asJsonObject
        val lifecycleJson = JsonParser.parseString(lifecycle.toJson()).asJsonObject
        val agentJson = JsonParser.parseString(agent.toJson()).asJsonObject

        assertEquals(10L, snapshotJson.get("snapshotEventSeq").asLong)
        assertEquals("vm-1", snapshotJson.getAsJsonArray("vms")[0].asJsonObject.get("vmId").asString)
        assertEquals("READY", lifecycleJson.get("current").asString)
        assertEquals(2, agentJson.get("protocolVersion").asInt)
        assertEquals(2, agentJson.getAsJsonArray("capabilities").size())
    }
}

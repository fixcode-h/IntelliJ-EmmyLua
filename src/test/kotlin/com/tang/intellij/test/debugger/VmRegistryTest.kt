package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.emmy.EmmyV2Envelope
import com.tang.intellij.lua.debugger.emmy.EmmyV2Target
import com.tang.intellij.lua.debugger.emmy.VmApplyStatus
import com.tang.intellij.lua.debugger.emmy.VmDto
import com.tang.intellij.lua.debugger.emmy.VmLifecycleDto
import com.tang.intellij.lua.debugger.emmy.VmRegistry
import com.tang.intellij.lua.debugger.emmy.VmSnapshotDto
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmRegistryTest {
    @Test
    fun `snapshot and lifecycle are idempotent and detect gaps`() {
        val registry = VmRegistry()
        val vm = VmDto("vm-1", 1, "PIE", "READY", "5.4.3", "HOST_API")

        assertEquals(VmApplyStatus.APPLIED, registry.applySnapshot(VmSnapshotDto(4, listOf(vm)), "agent", 1).status)
        assertEquals(VmApplyStatus.DUPLICATE, registry.applySnapshot(VmSnapshotDto(4, listOf(vm)), "agent", 1).status)
        assertEquals(VmApplyStatus.GAP, registry.applyLifecycle(
            VmLifecycleDto("vm-1", 1, "READY", "RUNNING", eventSeq = 6), "agent", 1
        ).status)
        assertEquals(VmApplyStatus.APPLIED, registry.applyLifecycle(
            VmLifecycleDto("vm-1", 1, "READY", "RUNNING", eventSeq = 5), "agent", 1
        ).status)
        assertEquals("RUNNING", registry.resolve("vm-1")?.state)
    }

    @Test
    fun `new connection epoch requires a snapshot and stale events are rejected`() {
        val registry = VmRegistry()
        val vm = VmDto("vm-1", 1, "PIE", "READY", null, "HOST_API")
        registry.applySnapshot(VmSnapshotDto(3, listOf(vm)), "agent", 1)

        assertEquals(VmApplyStatus.STALE_EPOCH, registry.applyLifecycle(
            VmLifecycleDto("vm-1", 1, "READY", "RUNNING", eventSeq = 4), "agent", 0
        ).status)
        assertEquals(VmApplyStatus.STALE_EPOCH, registry.applyLifecycle(
            VmLifecycleDto("vm-1", 1, "READY", "RUNNING", eventSeq = 1), "agent-2", 2
        ).status)
        assertEquals(VmApplyStatus.APPLIED, registry.applySnapshot(
            VmSnapshotDto(1, listOf(vm.copy(state = "RUNNING"))), "agent-2", 2
        ).status)
        assertEquals("RUNNING", registry.resolve("vm-1")?.state)
    }

    @Test
    fun `new epoch accepts zero snapshot even when event sequence repeats`() {
        val registry = VmRegistry()
        val vm = VmDto("vm-1", 1, "PIE", "READY", null, "HOST_API")
        assertEquals(VmApplyStatus.APPLIED, registry.applySnapshot(VmSnapshotDto(5, listOf(vm)), "agent", 1).status)
        assertEquals(VmApplyStatus.APPLIED, registry.applySnapshot(VmSnapshotDto(0, listOf(vm.copy(state = "RUNNING"))), "agent", 2).status)
        assertEquals("RUNNING", registry.resolve("vm-1")?.state)
        assertEquals(VmApplyStatus.STALE_EPOCH, registry.applyLifecycle(
            VmLifecycleDto("vm-1", 1, "RUNNING", "READY", eventSeq = 1), "agent", 1
        ).status)
    }

    @Test
    fun `legacy attached is selectable only when it is the sole VM`() {
        val registry = VmRegistry()
        val legacy = registry.legacyAttached(0x1234)
        assertEquals(legacy, registry.resolve())
        registry.applyLifecycle(
            VmLifecycleDto("vm-2", 1, null, "READY", eventSeq = 1)
        )
        assertNull(registry.resolve())
        assertTrue(registry.list().size == 2)
    }

    @Test
    fun `legacy native vm id resolves to the registered legacy record`() {
        val registry = VmRegistry()
        val legacy = registry.legacyAttached(0x1234, connectionEpoch = 1)

        // A legacy agent reports `vm-1`; its pause must land on the registered record.
        assertEquals(legacy.vmId, registry.normalizeLegacyVmId("vm-1"))
        // The mapping is stable across calls.
        assertEquals(legacy.vmId, registry.normalizeLegacyVmId("vm-1"))
        // An id that already names a record is returned unchanged.
        assertEquals(legacy.vmId, registry.normalizeLegacyVmId(legacy.vmId))

        registry.setPause(registry.normalizeLegacyVmId("vm-1")!!, 7)
        assertEquals(7L, registry.resolve(legacy.vmId)?.activePauseId)
        assertNull(registry.resolve("vm-1"))
    }

    @Test
    fun `legacy native vm id is never aliased onto unknown or v2 records`() {
        // Nothing registered yet: the caller keeps the native id.
        assertNull(VmRegistry().normalizeLegacyVmId("vm-1"))

        // Two legacy records: never guess which one a native id belongs to.
        val ambiguous = VmRegistry()
        ambiguous.legacyAttached(0x1234, connectionEpoch = 1)
        ambiguous.legacyAttached(0x5678, connectionEpoch = 1)
        assertNull(ambiguous.normalizeLegacyVmId("vm-1"))

        // v2 records are authoritative and must not absorb an older protocol id.
        val v2 = VmRegistry()
        val vm = VmDto("vm-9", 1, "PIE", "READY", null, "HOST_API")
        assertEquals(VmApplyStatus.APPLIED, v2.applySnapshot(VmSnapshotDto(1, listOf(vm)), "agent", 1).status)
        assertNull(v2.normalizeLegacyVmId("vm-1"))
        assertEquals("vm-9", v2.normalizeLegacyVmId("vm-9"))
    }

    @Test
    fun `closed generation cannot be resurrected and newer generation is accepted`() {
        val registry = VmRegistry()
        val vm = VmDto("vm-reused", 1, "PIE", "READY", null, "HOST_API")
        assertEquals(VmApplyStatus.APPLIED, registry.applySnapshot(VmSnapshotDto(1, listOf(vm)), "agent", 1).status)
        assertEquals(VmApplyStatus.APPLIED, registry.applyLifecycle(
            VmLifecycleDto("vm-reused", 1, "READY", "CLOSING", eventSeq = 2), "agent", 1
        ).status)
        assertEquals(VmApplyStatus.APPLIED, registry.applyLifecycle(
            VmLifecycleDto("vm-reused", 1, "CLOSING", "CLOSED", eventSeq = 3), "agent", 1
        ).status)
        assertEquals(VmApplyStatus.STALE_GENERATION, registry.applyLifecycle(
            VmLifecycleDto("vm-reused", 1, null, "READY", eventSeq = 4), "agent", 1
        ).status)
        assertEquals(VmApplyStatus.APPLIED, registry.applyLifecycle(
            VmLifecycleDto("vm-reused", 2, null, "CREATED", eventSeq = 4), "agent", 1
        ).status)
        assertEquals(2L, registry.resolve("vm-reused")?.generation)
    }

    @Test
    fun `v2 identity is all or nothing and control readiness is explicit`() {
        val registry = VmRegistry()
        val vm = VmDto("vm-1", 1, "PIE", "CREATED", null, "HOST_API")
        assertEquals(VmApplyStatus.STALE_EPOCH, registry.applySnapshot(VmSnapshotDto(0, listOf(vm)), "agent", null).status)
        assertEquals(VmApplyStatus.APPLIED, registry.applySnapshot(VmSnapshotDto(0, listOf(vm)), "agent", 1).status)
        assertTrue(!registry.isControlReady("vm-1"))
        assertEquals(VmApplyStatus.APPLIED, registry.applyLifecycle(
            VmLifecycleDto("vm-1", 1, "CREATED", "READY", eventSeq = 1), "agent", 1
        ).status)
        assertTrue(registry.isControlReady("vm-1"))
        assertEquals(VmApplyStatus.INVALID, registry.applyEnvelope(
            EmmyV2Envelope(kind = "event", type = "vm.lifecycle", payload = com.google.gson.JsonObject())
        ).status)
    }

    @Test
    fun `malformed v2 payload is returned as invalid instead of throwing`() {
        val payload = com.google.gson.JsonObject().apply { addProperty("generation", "not-a-number") }
        val result = VmRegistry().applyEnvelope(
            EmmyV2Envelope(kind = "event", type = "vm.snapshot", agentSessionId = "agent", connectionEpoch = 1,
                payload = payload)
        )
        assertEquals(VmApplyStatus.INVALID, result.status)
    }

    @Test
    fun `missing v2 snapshot payload reports the message used by attach diagnostics`() {
        val result = VmRegistry().applyEnvelope(
            EmmyV2Envelope(kind = "event", type = "vm.snapshot", agentSessionId = "agent", connectionEpoch = 1)
        )
        assertEquals(VmApplyStatus.INVALID, result.status)
        assertEquals("vm.snapshot payload is missing", result.message)
    }

    @Test
    fun `v2 handshake discards a pre-handshake legacy attach and re-arms the fence`() {
        val registry = VmRegistry()
        // AttachedNotify can beat InitRsp: it registers legacy-* and clears the fence.
        registry.legacyAttached(0x1234)
        assertTrue(!registry.isAwaitingSnapshot())
        assertEquals(1, registry.list().size)

        registry.rearmSnapshotFenceForV2()
        assertTrue(registry.isAwaitingSnapshot())
        assertTrue(registry.list().isEmpty())

        // The authoritative snapshot then registers the real VM the pause refers to.
        val vm = VmDto("vm-1", 1, "PIE", "PAUSED", null, "HOOK_FALLBACK")
        assertEquals(VmApplyStatus.APPLIED, registry.applySnapshot(VmSnapshotDto(3, listOf(vm)), "agent", 1).status)
        assertTrue(!registry.isAwaitingSnapshot())
        assertEquals("vm-1", registry.resolve("vm-1")?.vmId)
    }
}

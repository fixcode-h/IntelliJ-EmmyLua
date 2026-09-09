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
}

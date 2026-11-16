package com.tang.intellij.lua.debug.engine

import com.tang.intellij.lua.debug.protocol.VmInfo
import com.tang.intellij.lua.debug.protocol.VmStateChangedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则沿用旧 VmRegistryTest 里仍适用于 v3 的部分（v1/v2 legacy 归一化在 v3 里不存在了）。 */
class VmRegistryTest {
    @Test
    fun `重连后以握手快照为准 不在快照里的视为关闭`() {
        val registry = VmRegistry()
        registry.applySnapshot(listOf(VmInfo(1, "A"), VmInfo(2, "B")))
        val changes = registry.applySnapshot(listOf(VmInfo(2, "B", sourceEpoch = 2), VmInfo(3, "C")))
        assertEquals(listOf(1L), changes.closed)
        assertEquals(listOf(3L), changes.added.map { it.vmId })
        assertEquals(listOf(2L), changes.changed.map { it.vmId })
        assertEquals(listOf(2L, 3L), registry.list().map { it.vmId })
    }

    @Test
    fun `关闭过的 vmId 不能被迟到事件复活`() {
        val registry = VmRegistry()
        registry.add(VmInfo(1, "A"))
        registry.close(1)
        assertNull(registry.add(VmInfo(1, "A")))
        assertNull(registry.ensure(1))
        assertTrue(registry.applySnapshot(listOf(VmInfo(1, "A"))).added.isEmpty())
    }

    @Test
    fun `未知 VM 建占位记录 正式上报后替换`() {
        val registry = VmRegistry()
        val placeholder = registry.ensure(5)!!
        assertTrue(placeholder.placeholder)
        val real = registry.add(VmInfo(5, "PIE-0"))!!
        assertEquals("PIE-0", real.name)
        assertEquals(false, real.placeholder)
    }

    @Test
    fun `状态变化只对已知 VM 生效`() {
        val registry = VmRegistry()
        assertNull(registry.applyState(VmStateChangedEvent(9, "running", 2)))
        registry.add(VmInfo(1, "A"))
        assertEquals(2L, registry.applyState(VmStateChangedEvent(1, "running", 2))!!.sourceEpoch)
    }

    @Test
    fun `换进程后清空墓碑`() {
        val registry = VmRegistry()
        registry.add(VmInfo(1, "A"))
        registry.close(1)
        registry.resetProcess()
        assertEquals("A", registry.add(VmInfo(1, "A"))!!.name)
    }
}

package com.tang.intellij.lua.debug.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则沿用旧 PauseSnapshotStoreTest。 */
class PauseStoreTest {
    private fun pause(vm: Long, id: Long, epoch: Long = 1) = PauseInfo(vm, id, "t$vm", "breakpoint", null, connectionEpoch = epoch)

    @Test
    fun `已结束或被取代的暂停不能被迟到事件复活`() {
        val store = PauseStore()
        assertEquals(PauseStore.OfferStatus.FOCUSED, store.offer(pause(1, 2)))
        store.release(1, 2)
        assertEquals(PauseStore.OfferStatus.STALE, store.offer(pause(1, 2)))
        assertEquals(PauseStore.OfferStatus.STALE, store.offer(pause(1, 1)))
        assertEquals(PauseStore.OfferStatus.FOCUSED, store.offer(pause(1, 3)))
        assertEquals(PauseStore.OfferStatus.DUPLICATE, store.offer(pause(1, 3)))
    }

    @Test
    fun `同时只呈现一个暂停 其余排队 焦点结束后轮到最早排队的`() {
        val store = PauseStore()
        store.offer(pause(1, 1))
        assertEquals(PauseStore.OfferStatus.QUEUED, store.offer(pause(2, 1)))
        assertEquals(PauseStore.OfferStatus.QUEUED, store.offer(pause(3, 1)))
        val release = store.release(1)!!
        assertTrue(release.focusChanged)
        assertEquals(2L, release.newFocus!!.vmId)
        assertEquals(3L, store.focus(3)!!.vmId)
        assertNull(store.focus(1))
    }

    @Test
    fun `连接代次不同的暂停视为过期`() {
        val store = PauseStore()
        val old = pause(1, 5, epoch = 1)
        store.offer(old)
        store.clear()
        assertFalse(store.isCurrent(old))
        store.offer(pause(1, 6, epoch = 2))
        assertFalse(store.isCurrent(old))
        assertTrue(store.isCurrent(pause(1, 6, epoch = 2)))
    }

    @Test
    fun `换进程后 pauseId 从头计数`() {
        val store = PauseStore()
        store.offer(pause(1, 9))
        store.resetProcess()
        assertEquals(PauseStore.OfferStatus.FOCUSED, store.offer(pause(1, 1)))
    }
}

package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.emmy.PauseSnapshot
import com.tang.intellij.lua.debugger.emmy.PauseSnapshotStore
import com.tang.intellij.lua.debugger.emmy.SourceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseSnapshotStoreTest {
    @Test
    fun `pause snapshots are bounded and invalidatable`() {
        val store = PauseSnapshotStore(maxEntries = 2)
        store.put(PauseSnapshot("vm-1", 1))
        store.put(PauseSnapshot("vm-1", 2))
        store.put(PauseSnapshot("vm-2", 1))

        assertNull(store.get("vm-1", 1))
        assertNotNull(store.get("vm-1", 2))
        store.invalidate("vm-1")
        assertNull(store.get("vm-1", 2))
        assertEquals(1, store.size())
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun `source identity rejects hash epoch or unverified mismatches`() {
        val base = SourceIdentity("file:///a.lua", "C:/a.lua", "hash-a", 2, verified = true)
        assertTrue(base.matches(base.copy(uri = "file:///other")))
        assertFalse(base.matches(base.copy(sourceHash = "hash-b")))
        assertFalse(base.matches(base.copy(loaderEpoch = 3)))
        assertFalse(base.matches(base.copy(verified = false)))
    }
}

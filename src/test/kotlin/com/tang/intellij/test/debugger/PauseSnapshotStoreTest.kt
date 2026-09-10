package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.emmy.PauseSnapshot
import com.tang.intellij.lua.debugger.emmy.PauseSnapshotStore
import com.tang.intellij.lua.debugger.emmy.PauseOfferStatus
import com.tang.intellij.lua.debugger.emmy.SourceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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

    @Test
    fun `pause offers keep one ui pause and queue other vms`() {
        val store = PauseSnapshotStore()
        assertTrue(store.offer(PauseSnapshot("vm-a", 1)).shouldPresent)
        assertEquals(PauseOfferStatus.QUEUED, store.offer(PauseSnapshot("vm-b", 1)).status)
        assertEquals(PauseOfferStatus.DUPLICATE, store.offer(PauseSnapshot("vm-a", 1)).status)
        assertEquals("vm-a", store.currentUiPause()?.vmId)
        assertEquals("vm-b", store.releaseCurrent()?.vmId)
    }

    @Test
    fun `source identity normalizes windows paths and marks missing files unverified`() {
        val expected = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "c:/project/script.lua"
        } else {
            "C:/Project/SCRIPT.lua"
        }
        assertEquals(expected, SourceIdentity.normalizePath("C:\\Project\\sub\\..\\SCRIPT.lua"))
        val missing = SourceIdentity.fromPath("C:\\does-not-exist\\script.lua")
        assertFalse(missing.verified)
        assertNull(missing.sourceHash)
    }

    @Test
    fun `source identity distinguishes complete and partial hashes`() {
        val small = Files.createTempFile("source-identity-small", ".lua")
        val large = Files.createTempFile("source-identity-large", ".lua")
        try {
            Files.write(small, "return 1\n".toByteArray())
            Files.write(large, ByteArray(1024 * 1024 + 1) { (it % 251).toByte() })

            val complete = SourceIdentity.fromPath(small.toString())
            val partial = SourceIdentity.fromPath(large.toString())

            assertTrue(complete.hashComplete)
            assertTrue(complete.verified)
            assertFalse(partial.hashComplete)
            assertFalse(partial.verified)
            assertNotNull(partial.sourceHash)
        } finally {
            Files.deleteIfExists(small)
            Files.deleteIfExists(large)
        }
    }
}

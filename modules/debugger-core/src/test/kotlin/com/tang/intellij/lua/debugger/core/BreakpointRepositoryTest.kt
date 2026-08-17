package com.tang.intellij.lua.debugger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakpointRepositoryTest {
    @Test
    fun `updates emit complete snapshots for one normalized file`() {
        val repository = BreakpointRepository()

        repository.upsert(DebugBreakpoint("first", "C:\\game\\script\\main.lua", 10))
        val snapshots = repository.upsert(DebugBreakpoint("second", "C:/game/script/./main.lua", 20))

        assertEquals(1, snapshots.size)
        assertEquals("C:/game/script/main.lua", snapshots.single().path)
        assertEquals(listOf(10, 20), snapshots.single().breakpoints.map { it.line })
    }

    @Test
    fun `removal keeps remaining breakpoints and finally emits empty snapshot`() {
        val repository = BreakpointRepository()
        repository.upsert(DebugBreakpoint("first", "/game/main.lua", 10))
        repository.upsert(DebugBreakpoint("second", "/game/main.lua", 20))

        assertEquals(listOf(20), repository.remove("first")!!.breakpoints.map { it.line })
        assertTrue(repository.remove("second")!!.breakpoints.isEmpty())
    }

    @Test
    fun `moving an id emits old and new file snapshots`() {
        val repository = BreakpointRepository()
        repository.upsert(DebugBreakpoint("same", "/game/old.lua", 1))

        val snapshots = repository.upsert(DebugBreakpoint("same", "/game/new.lua", 2))

        assertEquals(listOf("/game/old.lua", "/game/new.lua"), snapshots.map { it.path })
        assertTrue(snapshots.first().breakpoints.isEmpty())
        assertEquals(listOf(2), snapshots.last().breakpoints.map { it.line })
    }
}

package com.tang.intellij.lua.debugger.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliControlProtocolTest {
    @Test
    fun `authorization and lease ownership are independent`() {
        val auth = AuthorizationService()
        auth.grant("target-1", "client-a")
        assertTrue(auth.canRead("target-1", "client-a"))
        assertFalse(auth.canRead("target-1", "client-b"))

        var now = 100L
        val leases = ControlLeaseManager(nowMillis = { now })
        val first = leases.acquire("target-1", "client-a", 10).getOrThrow()
        assertEquals("client-a", leases.current("target-1")?.owner)
        assertTrue(leases.acquire("target-1", "client-b", 10).isFailure)
        now = 111L
        assertEquals(null, leases.current("target-1"))
        assertTrue(leases.acquire("target-1", "client-b", 10).isSuccess)
        assertTrue(leases.release(first.leaseId).not())
    }

    @Test
    fun `value path evaluator rejects executable syntax`() {
        val evaluator = RestrictedValuePathEvaluator()
        val resolver = ValuePathResolver { segments -> CliValue("string", segments.joinToString(".")) }
        assertEquals("self.State", evaluator.evaluate("self.State", resolver).getOrThrow().display)
        assertTrue(evaluator.evaluate("require('x')", resolver).isFailure)
        assertTrue(evaluator.evaluate("obj.__index", resolver).isFailure)
        assertTrue(evaluator.evaluate("obj[foo()]", resolver).isFailure)
    }

    @Test
    fun `breakpoint composer preserves owners at the same location`() {
        val composer = BreakpointComposer()
        val key = BreakpointKey("hash:a", "vm-1", 12)
        composer.upsert(key, BreakpointContribution("USER", condition = "x > 0"))
        composer.upsert(key, BreakpointContribution("CLI:client-a", condition = "x == 3"))
        val composite = composer.snapshot().single()
        assertEquals(2, composite.contributions.size)
        assertTrue(composer.remove(key, "CLI:client-a"))
        assertEquals(1, composer.snapshot().single().contributions.size)
    }

    @Test
    fun `breakpoint composer preserves multiple ids from the same owner`() {
        val composer = BreakpointComposer()
        val key = BreakpointKey("c:/game/a.lua", "vm-1", 9)
        composer.upsert(key, BreakpointContribution("CLI:client-a", "bp-1", condition = "x == 1"))
        composer.upsert(key, BreakpointContribution("CLI:client-a", "probe:p-1", condition = "x == 2"))

        assertEquals(2, composer.snapshot().single().contributions.size)
        assertTrue(composer.remove(key, "CLI:client-a", "bp-1"))
        assertEquals("probe:p-1", composer.snapshot().single().contributions.single().breakpointId)
    }
}

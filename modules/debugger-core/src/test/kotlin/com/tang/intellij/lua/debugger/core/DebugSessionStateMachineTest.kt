package com.tang.intellij.lua.debugger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugSessionStateMachineTest {
    @Test
    fun `valid lifecycle reaches running and terminated`() {
        val machine = DebugSessionStateMachine()

        assertTransition(machine, DebugSessionEvent.START, DebugSessionState.PREPARING)
        assertTransition(machine, DebugSessionEvent.TARGET_READY, DebugSessionState.CONNECTING)
        assertTransition(machine, DebugSessionEvent.CONNECTED, DebugSessionState.INITIALIZING)
        assertTransition(machine, DebugSessionEvent.INITIALIZED, DebugSessionState.RUNNING)
        assertTransition(machine, DebugSessionEvent.STOP_REQUESTED, DebugSessionState.STOPPING)
        assertTransition(machine, DebugSessionEvent.TERMINATED, DebugSessionState.TERMINATED)
        assertTrue(machine.state.isTerminal)
    }

    @Test
    fun `invalid and duplicate transitions are ignored`() {
        val machine = DebugSessionStateMachine()

        assertNull(machine.transition(DebugSessionEvent.CONNECTED))
        assertTransition(machine, DebugSessionEvent.START, DebugSessionState.PREPARING)
        assertTransition(machine, DebugSessionEvent.STOP_REQUESTED, DebugSessionState.STOPPING)
        assertNull(machine.transition(DebugSessionEvent.STOP_REQUESTED))
        assertEquals(DebugSessionState.STOPPING, machine.state)
    }

    @Test
    fun `reconnect returns active session to connecting`() {
        val machine = DebugSessionStateMachine()
        machine.transition(DebugSessionEvent.START)
        machine.transition(DebugSessionEvent.TARGET_READY)
        machine.transition(DebugSessionEvent.CONNECTED)
        machine.transition(DebugSessionEvent.INITIALIZED)

        assertTransition(machine, DebugSessionEvent.RECONNECTING, DebugSessionState.CONNECTING)
    }

    @Test
    fun `terminal state cannot be rewritten`() {
        val machine = DebugSessionStateMachine()
        machine.transition(DebugSessionEvent.FAILED)

        assertNull(machine.transition(DebugSessionEvent.TERMINATED))
        assertEquals(DebugSessionState.FAILED, machine.state)
    }

    private fun assertTransition(
        machine: DebugSessionStateMachine,
        event: DebugSessionEvent,
        expected: DebugSessionState
    ) {
        assertEquals(expected, machine.transition(event)?.current)
        assertEquals(expected, machine.state)
    }
}

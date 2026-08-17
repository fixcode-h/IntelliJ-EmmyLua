package com.tang.intellij.lua.debugger.core

enum class DebugSessionState {
    CREATED,
    PREPARING,
    CONNECTING,
    INITIALIZING,
    RUNNING,
    STOPPING,
    TERMINATED,
    FAILED;

    val isTerminal: Boolean
        get() = this == TERMINATED || this == FAILED
}

enum class DebugSessionEvent {
    START,
    TARGET_READY,
    CONNECTED,
    INITIALIZED,
    RECONNECTING,
    STOP_REQUESTED,
    TERMINATED,
    FAILED
}

data class DebugSessionTransition(
    val previous: DebugSessionState,
    val current: DebugSessionState,
    val event: DebugSessionEvent
)

/** Pure state reducer for one debugger session generation. */
class DebugSessionStateMachine {
    var state: DebugSessionState = DebugSessionState.CREATED
        private set

    fun transition(event: DebugSessionEvent): DebugSessionTransition? {
        val next = reduce(state, event) ?: return null
        val transition = DebugSessionTransition(state, next, event)
        state = next
        return transition
    }

    private fun reduce(current: DebugSessionState, event: DebugSessionEvent): DebugSessionState? = when (event) {
        DebugSessionEvent.START -> if (current == DebugSessionState.CREATED) DebugSessionState.PREPARING else null
        DebugSessionEvent.TARGET_READY -> if (current == DebugSessionState.PREPARING) DebugSessionState.CONNECTING else null
        DebugSessionEvent.CONNECTED -> if (current == DebugSessionState.CONNECTING) DebugSessionState.INITIALIZING else null
        DebugSessionEvent.INITIALIZED -> if (current == DebugSessionState.INITIALIZING) DebugSessionState.RUNNING else null
        DebugSessionEvent.RECONNECTING -> when (current) {
            DebugSessionState.CONNECTING -> DebugSessionState.CONNECTING
            DebugSessionState.INITIALIZING, DebugSessionState.RUNNING -> DebugSessionState.CONNECTING
            else -> null
        }
        DebugSessionEvent.STOP_REQUESTED -> when {
            current.isTerminal || current == DebugSessionState.STOPPING -> null
            else -> DebugSessionState.STOPPING
        }
        DebugSessionEvent.TERMINATED -> when {
            current.isTerminal -> null
            else -> DebugSessionState.TERMINATED
        }
        DebugSessionEvent.FAILED -> when {
            current.isTerminal -> null
            else -> DebugSessionState.FAILED
        }
    }
}

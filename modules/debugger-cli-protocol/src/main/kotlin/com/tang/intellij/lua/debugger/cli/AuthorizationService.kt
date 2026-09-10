package com.tang.intellij.lua.debugger.cli

class AuthorizationService {
    private val lock = Any()
    private val grants = mutableMapOf<String, MutableSet<String>>()
    private val listeners = mutableListOf<(String, String, Boolean) -> Unit>()

    fun grant(targetId: String, clientId: String) {
        require(targetId.isNotBlank() && clientId.isNotBlank()) { "targetId and clientId must be non-blank" }
        val callbacks = synchronized(lock) {
            grants.getOrPut(targetId) { mutableSetOf() }.add(clientId)
            listeners.toList()
        }
        // Listener code may call back into the service or into the debugger;
        // never invoke it while holding the registry lock.
        callbacks.forEach { runCatching { it(targetId, clientId, true) } }
    }

    fun revoke(targetId: String, clientId: String) {
        val callbacks = synchronized(lock) {
            grants[targetId]?.let {
                it.remove(clientId)
                if (it.isEmpty()) grants.remove(targetId)
            }
            listeners.toList()
        }
        callbacks.forEach { runCatching { it(targetId, clientId, false) } }
    }

    fun canRead(targetId: String, clientId: String): Boolean = synchronized(lock) {
        grants[targetId]?.contains(clientId) == true
    }

    fun clear() = synchronized(lock) { grants.clear() }

    fun addListener(listener: (targetId: String, clientId: String, granted: Boolean) -> Unit): AutoCloseable {
        synchronized(lock) { listeners += listener }
        return AutoCloseable { synchronized(lock) { listeners.remove(listener) } }
    }

    fun clients(targetId: String): Set<String> = synchronized(lock) {
        grants[targetId]?.toSet() ?: emptySet()
    }
}

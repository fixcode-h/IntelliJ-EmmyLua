package com.tang.intellij.lua.debugger.cli

class AuthorizationService {
    private val grants = mutableMapOf<String, MutableSet<String>>()

    @Synchronized
    fun grant(targetId: String, clientId: String) {
        grants.getOrPut(targetId) { mutableSetOf() }.add(clientId)
    }

    @Synchronized
    fun revoke(targetId: String, clientId: String) {
        grants[targetId]?.let {
            it.remove(clientId)
            if (it.isEmpty()) grants.remove(targetId)
        }
    }

    @Synchronized
    fun canRead(targetId: String, clientId: String): Boolean = grants[targetId]?.contains(clientId) == true

    @Synchronized
    fun clear() = grants.clear()
}

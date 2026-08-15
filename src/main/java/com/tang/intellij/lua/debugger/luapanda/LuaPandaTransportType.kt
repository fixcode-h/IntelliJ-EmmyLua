package com.tang.intellij.lua.debugger.luapanda

import com.tang.intellij.lua.LuaBundle

enum class LuaPandaTransportType(val configId: String, val desc: String) {
    TCP_CLIENT("tcp-client", LuaBundle.message("debugger.transport.tcp_client")),
    TCP_SERVER("tcp-server", LuaBundle.message("debugger.transport.tcp_server"));

    override fun toString(): String = desc

    companion object {
        fun fromStoredValue(value: String?): LuaPandaTransportType? {
            if (value == null) return null
            return entries.firstOrNull { it.configId == value || it.name == value }
                ?: value.toIntOrNull()?.let(entries::getOrNull)
        }
    }
}

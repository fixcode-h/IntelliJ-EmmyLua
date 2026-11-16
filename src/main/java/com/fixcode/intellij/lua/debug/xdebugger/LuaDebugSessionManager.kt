package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSession
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.engine.DebugSession
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 项目里正在进行的新引擎调试会话。IDE 界面和 MCP 都从这里找会话，所以两边看到的是同一个
 * [DebugSession]：Agent 下的断点、IDE 里点的继续，彼此都看得见。
 *
 * 会话由 [LuaV3DebugProcess] 创建时登记、停止时注销，这里不自己建会话。
 */
@Service(Service.Level.PROJECT)
class LuaDebugSessionManager(private val project: Project) {
    class Entry(
        val engine: DebugSession,
        val target: LuaDebugTarget,
        val xSession: XDebugSession
    ) {
        val sessionId: String get() = engine.id
    }

    interface Listener {
        fun sessionAdded(entry: Entry) {}
        fun sessionRemoved(entry: Entry) {}
    }

    /** 保持开始顺序：MCP 的 status 按这个顺序列，只有一个会话时省略 session_id。 */
    private val sessions = LinkedHashMap<String, Entry>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun sessions(): List<Entry> = synchronized(sessions) { sessions.values.toList() }

    fun find(sessionId: String): Entry? = synchronized(sessions) { sessions[sessionId] }

    fun register(entry: Entry) {
        synchronized(sessions) { sessions[entry.sessionId] = entry }
        listeners.forEach { it.sessionAdded(entry) }
    }

    fun unregister(sessionId: String) {
        val entry = synchronized(sessions) { sessions.remove(sessionId) } ?: return
        listeners.forEach { it.sessionRemoved(entry) }
    }

    /** 监听器在登记 / 注销的线程上调用（EDT 或会话线程），要自己切线程。 */
    fun addListener(listener: Listener, parent: Disposable) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    companion object {
        fun getInstance(project: Project): LuaDebugSessionManager = project.service()
    }
}

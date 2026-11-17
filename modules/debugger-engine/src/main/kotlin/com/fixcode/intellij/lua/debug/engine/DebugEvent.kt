package com.fixcode.intellij.lua.debug.engine

import com.fixcode.intellij.lua.debug.protocol.BreakpointHitEvent
import com.fixcode.intellij.lua.debug.protocol.LuaErrorDto
import com.fixcode.intellij.lua.debug.protocol.OutputEvent
import com.fixcode.intellij.lua.debug.protocol.Welcome
import java.util.concurrent.CopyOnWriteArrayList

/** 引擎对外的事件。UI 和 MCP 订阅同一条事件流，看到的是同一份状态。 */
sealed class DebugEvent {
    data class StateChanged(val previous: SessionState, val current: SessionState, val message: String? = null) : DebugEvent()

    /** 握手完成（首次连接和每次重连后都会发）。 */
    data class Connected(val welcome: Welcome, val reconnect: Boolean) : DebugEvent()

    data class Disconnected(val reason: String, val willReconnect: Boolean) : DebugEvent()

    data class VmAdded(val vm: VmView) : DebugEvent()
    data class VmChanged(val vm: VmView) : DebugEvent()
    data class VmClosed(val vmId: Long, val reason: String?) : DebugEvent()

    /**
     * 某个 VM 暂停了。[focused] 为 true 表示这是 UI 当前要呈现的暂停；
     * 其他 VM 同时暂停时排队（[focused] = false），当前暂停结束后自动轮到它，届时发 [FocusChanged]。
     */
    data class Paused(val pause: PauseInfo, val focused: Boolean) : DebugEvent()

    data class Resumed(val vmId: Long, val pauseId: Long, val by: String?, val initiator: Initiator) : DebugEvent()

    /** UI 应切到这个暂停（排队的暂停轮到了，或用户切换了 VM）。为空表示没有暂停了。 */
    data class FocusChanged(val pause: PauseInfo?) : DebugEvent()

    /** 这些断点的状态或内容变了；[removedIds] 是被删掉的。 */
    data class BreakpointsChanged(val breakpoints: List<BreakpointView>, val removedIds: List<String> = emptyList()) : DebugEvent()

    data class BreakpointHit(val seq: Long, val hit: BreakpointHitEvent, val localPath: String?) : DebugEvent()

    data class Output(val seq: Long, val output: OutputEvent) : DebugEvent()

    /** 错误聚合（协议第 21 节）里新出现或计数变了的一条；[error] 已和本地缓存合并（保留第一次的 traceback）。 */
    data class LuaError(val error: LuaErrorDto) : DebugEvent()

    /** 错误聚合被清空。 */
    data object ErrorsCleared : DebugEvent()

    /** runtime 报告的内容与本地文件不一致（热更新、没同步），断点行号可能不准。 */
    data class SourceMismatch(val breakpointId: String, val vmId: Long, val chunkName: String?, val message: String?) : DebugEvent()

    data class Terminated(val reason: String) : DebugEvent()
}

fun interface DebugEventListener {
    fun onEvent(event: DebugEvent)
}

/**
 * 事件总线。监听器在会话线程上同步回调：要快，不能阻塞（UI 自己切 EDT，MCP 只做记录和唤醒）。
 * 监听器抛出的异常只记日志，不影响其他监听器和会话。
 */
class EventBus(private val errorHandler: (Throwable) -> Unit = {}) {
    private val listeners = CopyOnWriteArrayList<DebugEventListener>()

    fun subscribe(listener: DebugEventListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    fun publish(event: DebugEvent) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (error: Throwable) {
                errorHandler(error)
            }
        }
    }
}

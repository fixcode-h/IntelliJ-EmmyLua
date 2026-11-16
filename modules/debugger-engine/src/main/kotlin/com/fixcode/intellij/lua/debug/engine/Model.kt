package com.fixcode.intellij.lua.debug.engine

import com.fixcode.intellij.lua.debug.protocol.ErrorCodes
import com.fixcode.intellij.lua.debug.protocol.ExceptionInfo
import com.fixcode.intellij.lua.debug.protocol.PauseTarget
import com.fixcode.intellij.lua.debug.protocol.StackFrameDto
import com.fixcode.intellij.lua.debug.protocol.VariableDto

/** 引擎报给客户端（UI / MCP）的错误。[code] 是协议错误码或引擎本地码，[message] 是可以原样展示的中文。 */
open class DebugEngineException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** 引用已经失效：游戏已继续运行、VM 已关闭或连接已重建。UI 静默丢弃，MCP 原样把恢复提示给 Agent。 */
class StaleReferenceException(message: String = STALE_HINT) : DebugEngineException(ErrorCodes.STALE_REFERENCE, message) {
    companion object {
        const val STALE_HINT = "这个栈帧或变量已失效（游戏已继续运行）。请等下一次暂停，或重新获取调用栈后再试。"
    }
}

enum class SessionState {
    CREATED,
    CONNECTING,
    /** 已握手，正在下发 helpers / 选项 / 断点，configurationDone 之前。 */
    CONFIGURING,
    RUNNING,
    /** 连接意外断开，按重连策略重试中。 */
    RECONNECTING,
    STOPPING,
    TERMINATED,
    FAILED;

    val isTerminal: Boolean get() = this == TERMINATED || this == FAILED
    val isConnected: Boolean get() = this == CONFIGURING || this == RUNNING
}

/** 谁发起的操作。MCP 用它区分「用户手动继续了」和「Agent 自己继续的」。 */
enum class Initiator { USER, AGENT, ENGINE }

/**
 * 一次暂停。frame / 变量引用都绑定在它上面；它被继续、被新暂停取代、所在 VM 关闭或连接重建后，
 * 所有引用都失效（[DebugSession.isCurrent] 返回 false）。
 */
data class PauseInfo(
    val vmId: Long,
    val pauseId: Long,
    val threadId: String?,
    val reason: String,
    val top: StackFrameDto?,
    val breakpointIds: List<String> = emptyList(),
    val description: String? = null,
    val exception: ExceptionInfo? = null,
    /** 连接代次：重连后 frameId / ref 全部换新，旧代次的暂停一律视为过期。 */
    val connectionEpoch: Long = 0
) {
    fun target(): PauseTarget = PauseTarget(vmId, pauseId, threadId)
}

/** 栈帧句柄：DTO + 所属暂停 + 映射到的本地文件。 */
data class FrameHandle(
    val pause: PauseInfo,
    val frame: StackFrameDto,
    /** 映射到的本地文件；C 帧、未映射时为空。 */
    val localPath: String?
) {
    val frameId: Long get() = frame.frameId
}

data class StackPage(val frames: List<FrameHandle>, val total: Int)

/** 可展开的变量容器（作用域或 table）。 */
data class VariableContainer(val pause: PauseInfo, val ref: Long, val name: String, val count: Int? = null)

data class VariableHandle(val pause: PauseInfo, val variable: VariableDto) {
    fun children(): VariableContainer? =
        if (variable.ref > 0) VariableContainer(pause, variable.ref, variable.name, variable.namedCount?.plus(variable.indexedCount ?: 0)) else null
}

data class VariablePage(
    val variables: List<VariableHandle>,
    val total: Int,
    /** 底部分组（继承成员、方法、元表），不计入 [total]。 */
    val groups: List<VariableHandle> = emptyList(),
    /** runtime 因字节上限少给了项。 */
    val truncated: Boolean = false
)

data class EvalValue(
    val pause: PauseInfo,
    val expression: String,
    val value: String,
    val type: String,
    val ref: Long,
    val typeName: String?
) {
    fun children(): VariableContainer? = if (ref > 0) VariableContainer(pause, ref, expression) else null
}

enum class BreakpointOrigin {
    /** 编辑器里打的断点。 */
    IDE,
    /** Agent 通过 MCP 下的断点；UI 用不同图标显示，可一键清除。 */
    AGENT,
    /** 运行到光标的临时断点（runtime 没有 runToLine 能力时的兜底）。 */
    TEMPORARY
}

/** 客户端要下的断点。文件是本地路径。 */
data class BreakpointRequest(
    val file: String,
    val line: Int,
    val condition: String? = null,
    val hitCondition: String? = null,
    val logMessage: String? = null,
    val capture: List<String> = emptyList(),
    val autoContinue: Boolean = false,
    val vmId: Long = 0,
    val origin: BreakpointOrigin = BreakpointOrigin.IDE,
    val enabled: Boolean = true
)

/** 一个断点的汇总状态：多个 VM 时，任一 VM 验证通过就算 verified。 */
enum class BreakpointVerification { PENDING, VERIFIED, MISMATCH, AMBIGUOUS, INVALID }

data class BreakpointView(
    val id: String,
    val request: BreakpointRequest,
    val verification: BreakpointVerification,
    val message: String? = null,
    /** runtime 调整后的实际行；没调整或未验证时为空。 */
    val actualLine: Int? = null
)

/** VM 在引擎里的视图。 */
data class VmView(
    val vmId: Long,
    val name: String,
    val state: String,
    val generation: Long,
    val sourceEpoch: Long,
    val luaVersion: String?,
    val paused: Boolean,
    /** runtime 没上报过、因为收到它的暂停才建的占位记录。 */
    val placeholder: Boolean = false
)

/** 带序号的记录（输出、Probe 命中），MCP 用 sinceSeq 增量读取。 */
data class Sequenced<T>(val seq: Long, val timeMillis: Long, val value: T)

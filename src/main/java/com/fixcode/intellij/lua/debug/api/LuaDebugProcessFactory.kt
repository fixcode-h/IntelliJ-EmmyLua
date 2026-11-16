package com.fixcode.intellij.lua.debug.api

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession

/**
 * UI 和引擎之间的接缝：UI 负责选目标、启动 XDebugSession；
 * 引擎（XDebugger 适配层）负责把一个目标变成 XDebugProcess。
 *
 * 由「IDE 调试引擎」实现并在 `emmylua-debug.xml` 里注册：
 * ```xml
 * <extensions defaultExtensionNs="com.fixcode.intellij.lua">
 *     <debug.processFactory implementation="...YourFactory"/>
 * </extensions>
 * ```
 * 没有实现注册时，UI 会提示"新调试引擎尚未就绪"，不会崩。
 */
interface LuaDebugProcessFactory {
    /** 是否能处理这种目标（例如 Android 实现可以只认 [LuaDebugTarget.AndroidDevice]）。 */
    fun supports(target: LuaDebugTarget): Boolean

    /**
     * 在 XDebuggerManager.startSession 的回调里调用（EDT）。
     * 连接、注入、握手要在后台做，失败时通过 session 报错并结束，不要阻塞这里。
     */
    fun createDebugProcess(session: XDebugSession, target: LuaDebugTarget): XDebugProcess

    companion object {
        val EP: ExtensionPointName<LuaDebugProcessFactory> =
            ExtensionPointName.create("com.fixcode.intellij.lua.debug.processFactory")

        fun find(target: LuaDebugTarget): LuaDebugProcessFactory? =
            EP.extensionList.firstOrNull { it.supports(target) }
    }
}

/**
 * 调试窗口里「VM」「映射」页要的数据。由引擎的 XDebugProcess 实现这个接口，
 * UI 从 `session.debugProcess as? LuaDebugSessionModel` 拿到后自己画页签，
 * 引擎不需要写任何 Swing 代码。
 *
 * 所有方法在 EDT 调用；变化通过 [addListener] 通知（监听器会被切回 EDT 再刷新）。
 */
interface LuaDebugSessionModel {
    fun vms(): List<LuaVmInfo>

    /** 当前选中的 VM（栈、变量、单步都作用于它）；为空表示还没有 VM 注册。 */
    fun currentVmId(): String?

    fun selectVm(vmId: String)

    /** 本会话遇到过的 chunkName 及其映射结果。 */
    fun sourceMappings(): List<LuaSourceMappingEntry>

    /** 用户在「映射」页给某个 chunk 手动指定本地文件；引擎记入规则表并重新校验断点。 */
    fun overrideMapping(chunkName: String, localPath: String)

    fun addListener(listener: Listener, parent: com.intellij.openapi.Disposable)

    interface Listener {
        fun vmsChanged() {}
        fun mappingsChanged() {}
    }
}

enum class LuaVmState { RUNNING, PAUSED, CLOSED }

data class LuaVmInfo(
    val vmId: String,
    val displayName: String,
    val state: LuaVmState,
    val sourceEpoch: Long,
    /** 例如 "Lua 5.4"、"LuaJIT 2.1"；runtime 没报时为空。 */
    val luaVersion: String? = null,
)

enum class LuaMappingStatus {
    /** 已映射，内容一致或 runtime 没报 hash。 */
    MAPPED,
    /** 没找到本地文件。 */
    UNMAPPED,
    /** 多个候选，等用户选。 */
    AMBIGUOUS,
    /** 已映射但 sha256 和本地不一致（热更新、没同步）。 */
    HASH_MISMATCH,
    /** 字节码去掉了调试信息，不能断点。 */
    STRIPPED,
}

data class LuaSourceMappingEntry(
    val chunkName: String,
    val localPath: String?,
    val status: LuaMappingStatus,
    val candidates: List<String> = emptyList(),
)

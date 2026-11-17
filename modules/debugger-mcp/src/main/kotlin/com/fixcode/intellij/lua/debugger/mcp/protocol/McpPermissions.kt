package com.fixcode.intellij.lua.debugger.mcp.protocol

import java.util.concurrent.ConcurrentHashMap

/**
 * 向用户确认的方式由 IDE 实现（气泡通知 + 按钮）。方法会阻塞调用线程直到用户回答或超时，
 * 所以只能在 MCP 的 HTTP 工作线程上调用，不能在 EDT 上调用。
 */
interface McpConfirmer {
    /**
     * 「Agent 请求控制调试会话 X：允许 / 本次会话总是允许 / 拒绝」。
     * [subject] 是给人看的对象描述，例如 "UE4Editor.exe (1234)" 或 "IDE 断点"。
     */
    fun confirmControl(subject: String, clientName: String?): ControlDecision

    /** 「Agent 想 [description]」（附加到进程、以 Debug 启动运行配置）：每次都确认。[description] 是动宾短语。 */
    fun confirmLaunch(description: String, clientName: String?): Boolean

    /** 设置里是否允许 evaluate 的 mode=lua（执行任意 Lua）。 */
    fun luaEvalAllowed(): Boolean
}

enum class ControlDecision {
    /** 只允许这一次调用。 */
    ALLOW_ONCE,
    /** 本次调试会话里之后都允许。 */
    ALLOW_SESSION,
    DENY,
    /** 用户没理（超时）。 */
    NO_ANSWER,
}

/**
 * 记住哪些调试会话已经授予了控制权。代替原 CLI 的授权菜单 + 租约：
 * 引擎单线程处理所有命令，UI 和 Agent 不会冲突，所以这里只需要"用户同意过没有"。
 */
class McpPermissionPolicy(private val confirmer: McpConfirmer) {
    private val granted = ConcurrentHashMap.newKeySet<String>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 返回 null 表示允许；否则是给 Agent 的中文拒绝原因。 */
    fun checkControl(grantKey: String, subject: String, clientName: String?): String? {
        if (grantKey in granted) return null
        return when (confirmer.confirmControl(subject, clientName)) {
            ControlDecision.ALLOW_ONCE -> null
            ControlDecision.ALLOW_SESSION -> { granted.add(grantKey); fireChanged(); null }
            ControlDecision.DENY -> "用户在 IDE 里拒绝了对「$subject」的控制。请不要重试，先询问用户。"
            ControlDecision.NO_ANSWER -> "用户没有在 IDE 里确认对「$subject」的控制（等待超时）。" +
                "请提醒用户在 IDE 右下角的通知里点「允许」后再重试。"
        }
    }

    fun checkLaunch(description: String, clientName: String?): String? =
        if (confirmer.confirmLaunch(description, clientName)) null
        else "用户没有同意$description（拒绝或超时）。请先询问用户，不要重试。"

    fun luaEvalAllowed(): Boolean = confirmer.luaEvalAllowed()

    fun isGranted(grantKey: String): Boolean = grantKey in granted

    /** 「撤销 Agent 控制」。 */
    fun revokeAll() {
        if (granted.isNotEmpty()) {
            granted.clear()
            fireChanged()
        }
    }

    /** 调试会话结束时忘掉它的授权。 */
    fun forget(grantKey: String) {
        if (granted.remove(grantKey)) fireChanged()
    }

    fun grantedKeys(): Set<String> = granted.toSet()

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun fireChanged() = listeners.forEach { runCatching { it() } }

    companion object {
        /** 没有会话的控制（下断点）用这个 key 记授权。 */
        const val BREAKPOINTS_KEY = "breakpoints"

        /** UE 热重载（lua.reload）不属于哪个调试会话，单独记授权。 */
        const val RELOAD_KEY = "ue-reload"
    }
}

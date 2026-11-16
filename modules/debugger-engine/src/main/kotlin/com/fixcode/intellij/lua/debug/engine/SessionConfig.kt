package com.fixcode.intellij.lua.debug.engine

import com.google.gson.JsonObject
import com.fixcode.intellij.lua.debug.protocol.HelperScript
import com.fixcode.intellij.lua.debug.protocol.SetOptionsArgs

/** 断线重连：真机 App 切后台、Wi-Fi 切换时连接会断，runtime 回前台后重建监听。 */
data class ReconnectPolicy(
    /** 0 表示不重连，断开即结束会话。 */
    val maxAttempts: Int = 10,
    val initialDelayMillis: Long = 500,
    val maxDelayMillis: Long = 5_000
) {
    fun delayFor(attempt: Int): Long =
        (initialDelayMillis * (1L shl (attempt - 1).coerceIn(0, 10))).coerceAtMost(maxDelayMillis)

    companion object {
        val NONE = ReconnectPolicy(maxAttempts = 0)
    }
}

data class SessionConfig(
    /** 会话显示名（UI 标题、MCP 的 list_sessions）。 */
    val name: String = "Lua",
    val clientVersion: String? = null,
    /** 附加时 IDE 生成并注入的 token，或局域网配对码。 */
    val token: String? = null,
    /** 类型显示脚本（emmyHelper.lua 等）的内容；runtime 有 helpers 能力时下发。 */
    val helpers: List<HelperScript> = emptyList(),
    val helperMain: String = "emmyHelper",
    val options: SetOptionsArgs = SetOptionsArgs(captureOutput = true),
    /** 显示规则（variable-view.md 11.1 的文件内容）；runtime 有 viewRules 能力时握手后下发。 */
    val viewRules: JsonObject? = null,
    /** variables.maxValueLength；只在协商了 richVariables 时发送。 */
    val maxValueLength: Int? = null,
    val requestTimeoutMillis: Long = 10_000,
    val handshakeTimeoutMillis: Long = 5_000,
    /** 心跳间隔，0 关闭（协议第 4 节：5 秒一次，连续 3 次无回应视为断线）。 */
    val heartbeatIntervalMillis: Long = 5_000,
    val heartbeatMaxMisses: Int = 3,
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
    val outputCapacity: Int = 5_000,
    val breakpointHitCapacity: Int = 5_000
)

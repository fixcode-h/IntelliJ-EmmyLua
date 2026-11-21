package com.fixcode.intellij.lua.debugger.mcp.protocol

import com.fixcode.intellij.lua.debugger.mcp.json.JsonArgs

/** 工具的权限级别，见设计文档 6.2。 */
enum class PermissionLevel {
    /** 只读：默认允许。 */
    READ,
    /** 控制（断点、继续、单步……）：每个调试会话第一次控制时要用户在 IDE 里确认。 */
    CONTROL,
    /** 启动/附加：每次都要用户确认。 */
    LAUNCH,
}

/**
 * 一个 MCP 工具。规范见 AGENTS.md「MCP 工具规范」（照 UEToVS）：
 * - [name] 是协议名，Agent 用它调用，不随版本改；[title] 是英文显示名，[titleZh] 是中文显示名，
 *   两个都放进 tools/list 的 `_meta.titles`，客户端按自己的界面语言选。
 * - [description] 用 [ToolGuide] 的固定结构写。
 * - [handler] 返回结果对象（序列化成紧凑 JSON，作为唯一的 text 内容）；出错抛 ToolException。
 */
class McpTool(
    val name: String,
    val title: String,
    val titleZh: String,
    val description: String,
    /** JSON Schema（object，additionalProperties=false）。 */
    val inputSchema: Map<String, Any?>,
    val level: PermissionLevel,
    /** 可能结束目标进程或丢数据（例如 stop 时游戏随调试器一起退出）。 */
    val destructive: Boolean = false,
    /**
     * 不写进 schema、但也接受的参数名。目前只有 IDE 自带工具通用的 `projectPath`：Agent 看惯了 IDE 工具都带它，
     * 给我们的工具也会顺手传（用户 2026-11-21 报 lua_code 因此整次失败）；它是 camelCase，不进 schema，免得破坏参数命名规范。
     */
    val hiddenArgs: Set<String> = emptySet(),
    val handler: (ToolCall) -> Map<String, Any?>,
) {
    /** 参数名（校验未知参数用）。 */
    @Suppress("UNCHECKED_CAST")
    val argumentNames: Set<String> = (inputSchema["properties"] as? Map<String, Any?>)?.keys.orEmpty() + hiddenArgs

    fun descriptor(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "title" to title,
        "description" to description,
        "inputSchema" to inputSchema,
        "annotations" to linkedMapOf(
            "title" to title,
            "readOnlyHint" to (level == PermissionLevel.READ),
            "destructiveHint" to destructive,
            "idempotentHint" to (level == PermissionLevel.READ),
            "openWorldHint" to false,
        ),
        "_meta" to linkedMapOf("titles" to linkedMapOf("zh" to titleZh, "en" to title)),
    )
}

/**
 * 工具说明的固定结构（照 UEToVS 的 ToolGuides）：Agent 只看说明就能判断这个工具是否比通用办法好。
 * 拼成 `What USE FIRST WHEN: … INSTEAD OF: … NOT FOR: … Results: …`，总长不超过 1200 字。
 */
class ToolGuide(
    val what: String,
    val useWhen: String,
    val insteadOf: String,
    val notFor: String,
    val results: String? = null,
) {
    fun render(): String = buildString {
        append(what)
        append(" USE FIRST WHEN: ").append(useWhen)
        append(" INSTEAD OF: ").append(insteadOf)
        append(" NOT FOR: ").append(notFor)
        if (!results.isNullOrEmpty()) append(" Results: ").append(results)
    }
}

class ToolCall(
    val tool: McpTool,
    val args: JsonArgs,
    val client: McpClientSession,
)

/** 一个 MCP 客户端（按 IDE MCP 服务器给的会话或客户端名区分，见 [McpToolDispatcher.client]）。 */
class McpClientSession(
    val id: String,
    val clientName: String?,
    val clientVersion: String?,
) {
    @Volatile
    var lastActiveMillis: Long = System.currentTimeMillis()

    /** 这个客户端已经通过 wait_for_pause / step 等看到过的 pauseId，避免同一次暂停反复返回。 */
    val seenPauseIds: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    fun markSeen(pauseId: String) {
        synchronized(seenPauseIds) {
            seenPauseIds.add(pauseId)
            // 只留最近的，防止长时间运行无限增长
            while (seenPauseIds.size > 512) seenPauseIds.remove(seenPauseIds.first())
        }
    }
}

/** 每次工具调用记一条日志（事件名 `mcp.tool.<工具名>`，慢调用 `.slow`、参数错 `.rejected`、内部错 `.failed`）。 */
fun interface McpLogger {
    fun log(level: Level, event: String, message: String?, fields: Map<String, Any?>, error: Throwable?)

    enum class Level { DEBUG, INFO, WARN, ERROR }

    companion object {
        val NONE = McpLogger { _, _, _, _, _ -> }
    }
}

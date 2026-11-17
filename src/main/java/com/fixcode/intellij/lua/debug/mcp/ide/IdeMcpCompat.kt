package com.fixcode.intellij.lua.debug.mcp.ide

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolDescriptor
import com.fixcode.intellij.lua.debugger.mcp.json.Json
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpTool as LuaTool
import com.fixcode.intellij.lua.debugger.mcp.protocol.PermissionLevel
import java.lang.reflect.Constructor
import kotlin.coroutines.CoroutineContext

/**
 * IDE 自带 MCP 服务器的接口在 2025.2–2026.3 之间一直在变，只有 `McpTool`、`McpToolsProvider`、
 * `McpToolCallResultContent.Text` 是稳定的。其余按版本用反射构造：
 *
 * | 版本 | McpToolDescriptor 构造参数 | 输入 schema | McpToolCallResult |
 * | --- | --- | --- | --- |
 * | 2025.2 | name, description, inputSchema | McpToolInputSchema(parameters, required, definitions, path) | (content, isError) |
 * | 2025.3 | name, description, input, output | McpToolSchema.ofPropertiesMap(...) | (content, structured, isError) |
 * | 2026.1 | name, description, category, fqn, input, output | 同上 | 同上 |
 * | 2026.2 | name, title, description, category, fqn, input, output, annotations | 同上 | 同上 |
 * | 2026.3 | name, title, description, displayDescription, category, fqn, input, output, annotations | 同上 | 同上 |
 *
 * 构造时用 Kotlin 生成的带默认参数的构造函数（最后两个参数是位掩码和 DefaultConstructorMarker），
 * 按参数类型填：认识的填值，不认识的置空并在掩码里标「用默认值」。这样新版本只是多了带默认值的参数时也能用。
 * 所有类都从 [loader] 取，单测里换成各版本的 jar 验证（McpCompatTest）。
 */
internal object IdeMcpCompat {
    /** 设置页里按类别分组时用（2026.1 起）；全名照 IDE 的写法用「类名」，工具全名是「类别全名.工具名」。 */
    const val CATEGORY_SHORT_NAME = "LuaDebug"
    const val CATEGORY_FQN = "com.fixcode.intellij.lua.debug.mcp.LuaDebugToolset"
    const val CATEGORY_DISPLAY_NAME = "EmmyLua (Lua debug & code)"
    private const val PKG = "com.intellij.mcpserver"

    fun descriptor(tool: LuaTool): McpToolDescriptor =
        descriptor(tool, McpToolDescriptor::class.java.classLoader) as McpToolDescriptor

    fun result(content: Array<McpToolCallResultContent>, isError: Boolean): McpToolCallResult =
        result(content, isError, McpToolCallResult::class.java.classLoader) as McpToolCallResult

    // ------------------------------------------------------------------ 描述

    fun descriptor(tool: LuaTool, loader: ClassLoader): Any {
        val cls = loader.loadClass("$PKG.McpToolDescriptor")
        val input = inputSchema(tool.inputSchema, loader)
        var stringsBeforeCategory = -1
        var schemas = 0
        return construct(cls) { index, type, params ->
            when {
                type == String::class.java -> {
                    val categoryAt = params.indexOfFirst { it.name == "$PKG.McpToolCategory" }
                    val stringIndex = params.take(index).count { it == String::class.java }
                    if (stringsBeforeCategory < 0) {
                        val end = if (categoryAt >= 0) categoryAt else params.indexOfFirst { it != String::class.java }.let { if (it < 0) params.size else it }
                        stringsBeforeCategory = params.take(end).count { it == String::class.java }
                    }
                    when {
                        categoryAt in 0 until index -> "$CATEGORY_FQN.${tool.name}" // 类别后面的字符串是工具全名
                        stringIndex == 0 -> tool.name
                        stringsBeforeCategory == 2 -> if (stringIndex == 1) describe(tool) else DEFAULT
                        stringIndex == 1 -> tool.title
                        stringIndex == 2 -> describe(tool)
                        else -> DEFAULT // displayDescription 等：用 IDE 的默认值
                    }
                }
                type.name == "$PKG.McpToolInputSchema" || type.name == "$PKG.McpToolSchema" ->
                    if (schemas++ == 0) input else DEFAULT // 第二个是输出 schema，不提供
                type.name == "$PKG.McpToolCategory" -> category(loader)
                type.name == "io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations" -> annotations(tool, type)
                else -> DEFAULT
            }
        }
    }

    /**
     * IDE 的 tools/list 不带 `_meta`（各版本的描述符都没有这个字段），2026.2 前连 title 也没有，
     * 所以中文名附在说明最后一行，面板按 [ZH_TITLE_PREFIX] 读出来当显示名（和面板约定，改格式要同步面板）。
     */
    fun describe(tool: LuaTool): String = "${tool.description}\n\n$ZH_TITLE_PREFIX${tool.titleZh}"

    const val ZH_TITLE_PREFIX = "Title (zh): "

    /** properties 每项转成 kotlinx 的 JsonElement；required 单独给。additionalProperties 由 IDE 自己生成 schema 时丢掉，靠 JsonArgs 拒绝未知参数。 */
    @Suppress("UNCHECKED_CAST")
    private fun inputSchema(schema: Map<String, Any?>, loader: ClassLoader): Any {
        val properties = (schema["properties"] as? Map<String, Any?>).orEmpty()
            .mapValues { (_, v) -> kotlinx.serialization.json.Json.parseToJsonElement(Json.write(v)) }
        val required = (schema["required"] as? List<String>).orEmpty().toSet()
        val newer = runCatching { loader.loadClass("$PKG.McpToolSchema") }.getOrNull()
        if (newer != null) {
            val companionField = newer.getField("Companion")
            val companion = companionField.get(null)
            val path = newer.getField("DEFAULT_DEFINITIONS_PATH").get(null) as String
            val m = companion.javaClass.getMethod("ofPropertiesMap", Map::class.java, Set::class.java, Map::class.java, String::class.java)
            return m.invoke(companion, properties, required, emptyMap<String, Any>(), path)
        }
        val older = loader.loadClass("$PKG.McpToolInputSchema")
        val path = older.getField("DEFAULT_DEFINITIONS_PATH").get(null) as String
        return older.getConstructor(Map::class.java, Set::class.java, Map::class.java, String::class.java)
            .newInstance(properties, required, emptyMap<String, Any>(), path)
    }

    private fun category(loader: ClassLoader): Any {
        val cls = loader.loadClass("$PKG.McpToolCategory")
        var strings = 0
        var booleans = 0
        return construct(cls) { _, type, _ ->
            when (type) {
                String::class.java -> when (strings++) {
                    0 -> CATEGORY_SHORT_NAME
                    1 -> CATEGORY_FQN
                    2 -> CATEGORY_DISPLAY_NAME // displayName（2026.3）
                    else -> DEFAULT // displayDescription（2026.3）
                }
                // (isExperimental, alwaysIncluded)，2026.2 起才有 alwaysIncluded：
                // 设成 true 后「仅路由器模式」下我们的工具仍直接列给 Agent（2026.2），2026.3 默认不勾 Router-only；
                // 用户仍可以在设置里关掉或改成 Router-only。
                java.lang.Boolean.TYPE -> if (booleans++ == 1) true else DEFAULT
                else -> DEFAULT
            }
        }
    }

    /** MCP SDK 的 ToolAnnotations(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint)，2026.2 起才有。 */
    private fun annotations(tool: LuaTool, type: Class<*>): Any {
        val readOnly = tool.level == PermissionLevel.READ
        val values = listOf<Any?>(tool.title, readOnly, tool.destructive, readOnly, false)
        val ctor = type.constructors.firstOrNull { c ->
            c.parameterTypes.size == 5 && c.parameterTypes[0] == String::class.java &&
                c.parameterTypes.drop(1).all { it == java.lang.Boolean::class.java }
        } ?: return construct(type) { _, _, _ -> DEFAULT }
        return ctor.newInstance(*values.toTypedArray())
    }

    // ------------------------------------------------------------------ 结果

    fun result(content: Array<*>, isError: Boolean, loader: ClassLoader): Any {
        val cls = loader.loadClass("$PKG.McpToolCallResult")
        return construct(cls) { _, type, _ ->
            when {
                type.isArray -> content.copyToTypedArrayOf(type.componentType)
                type == java.lang.Boolean.TYPE -> isError
                else -> DEFAULT // structuredContent（2025.3 起）：不给，内容就是 text 里的 JSON
            }
        }
    }

    private fun Array<*>.copyToTypedArrayOf(component: Class<*>): Any {
        val arr = java.lang.reflect.Array.newInstance(component, size)
        forEachIndexed { i, v -> java.lang.reflect.Array.set(arr, i, v) }
        return arr
    }

    // ------------------------------------------------------------------ 客户端

    class Client(val key: String?, val name: String?, val version: String?)

    /**
     * 当前调用的客户端：2025.3 起协程上下文里有 McpCallInfo（clientInfo；2026.2 起还有 sessionId），
     * 2025.2 没有，大家共用一个会话。
     */
    fun clientOf(context: CoroutineContext): Client = try {
        val kt = Class.forName("$PKG.McpCallInfoKt", false, McpToolDescriptor::class.java.classLoader)
        val info = kt.getMethod("getMcpCallInfoOrNull", CoroutineContext::class.java).invoke(null, context)
        if (info == null) Client(null, null, null) else {
            val clientInfo = runCatching { info.javaClass.getMethod("getClientInfo").invoke(info) }.getOrNull()
            val name = clientInfo?.let { runCatching { it.javaClass.getMethod("getName").invoke(it) as? String }.getOrNull() }
            val version = clientInfo?.let { runCatching { it.javaClass.getMethod("getVersion").invoke(it) as? String }.getOrNull() }
            val session = runCatching { info.javaClass.getMethod("getSessionId").invoke(info) as? String }.getOrNull()
            Client(session, name, version)
        }
    } catch (e: Throwable) {
        Client(null, null, null)
    }

    // ------------------------------------------------------------------ 构造

    /** 填值回调返回它表示「这个参数用默认值」。 */
    private val DEFAULT = Any()

    /**
     * 用带默认参数的构造函数（`(…, int mask, DefaultConstructorMarker)`）构造；没有就用参数最多的公开构造函数。
     * [fill] 按参数下标、类型给值，返回 [DEFAULT] 表示用默认值（基本类型给零值）。
     */
    private fun construct(cls: Class<*>, fill: (index: Int, type: Class<*>, params: List<Class<*>>) -> Any?): Any {
        val withDefaults = cls.constructors.filter { c ->
            val p = c.parameterTypes
            p.size >= 2 && p.last().name == "kotlin.jvm.internal.DefaultConstructorMarker" && p[p.size - 2] == Integer.TYPE
        }.maxByOrNull { it.parameterCount }
        if (withDefaults != null) {
            val params = withDefaults.parameterTypes.dropLast(2)
            var mask = 0
            val args = params.mapIndexed { i, type ->
                val v = fill(i, type, params)
                if (v === DEFAULT) {
                    mask = mask or (1 shl i)
                    zero(type)
                } else v
            }
            return withDefaults.newInstance(*(args + listOf(mask, null)).toTypedArray())
        }
        val plain: Constructor<*> = cls.constructors
            .filter { c -> c.parameterTypes.none { it.name.startsWith("kotlin.jvm.internal.") || it.name.startsWith("kotlinx.serialization.internal.") } }
            .maxByOrNull { it.parameterCount }
            ?: error("${cls.name} 没有可用的构造函数")
        val params = plain.parameterTypes.toList()
        val args = params.mapIndexed { i, type -> fill(i, type, params).let { if (it === DEFAULT) zero(type) else it } }
        return plain.newInstance(*args.toTypedArray())
    }

    private fun zero(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        Character.TYPE -> 0.toChar()
        else -> null
    }
}

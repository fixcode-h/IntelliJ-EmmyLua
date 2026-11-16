package com.fixcode.intellij.lua.project

import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * UnLua 的模块名换算和 `lua.reload` 回包解析。纯逻辑，不依赖 IDE，便于单测。
 *
 * UnLua 的模块名相对脚本根目录 `Content/Script`：`Content/Script/UI/BagPanel.lua` → `UI.BagPanel`。
 */
object UnrealLuaModules {
    const val SCRIPT_DIR = "Content/Script"

    /**
     * 文件 → 模块名；不是 `.lua` 或不在脚本根下时返回 null。
     * 给了 [ueRoot] 先按 `<ueRoot>/Content/Script` 算；不在那下面（插件里的脚本、IDE 只打开了脚本目录）时
     * 在路径里找最后一段 `Content/Script`（不分大小写，Windows 路径常大小写不一）。
     */
    fun moduleName(file: Path, ueRoot: Path?): String? {
        val path = file.toAbsolutePath().normalize()
        if (!path.fileName.toString().endsWith(".lua", ignoreCase = true)) return null
        if (ueRoot != null) {
            val scriptRoot = ueRoot.toAbsolutePath().normalize().resolve(SCRIPT_DIR)
            if (path.startsWith(scriptRoot)) return join(scriptRoot.relativize(path).map { it.toString() })
        }
        val segments = path.map { it.toString() }
        for (i in segments.size - 3 downTo 0) {
            if (segments[i].equals("Content", ignoreCase = true) && segments[i + 1].equals("Script", ignoreCase = true)) {
                return join(segments.subList(i + 2, segments.size))
            }
        }
        return null
    }

    private fun join(rel: List<String>): String? {
        if (rel.isEmpty()) return null
        val last = rel.last().let { it.substring(0, it.length - ".lua".length) }
        val parts = rel.dropLast(1) + last
        if (parts.any { it.isEmpty() || it.contains('.') }) return null // 带点的目录名换成模块名会歧义
        return parts.joinToString(".")
    }

    /**
     * 热重载发给哪些实例：编辑器（含 PIE）、`-game` 独立进程、专用服务器，只要开了 HTTP；
     * 后台编辑器（commandlet）不跑游戏 Lua，不发。编辑器排前面，同类按启动时间新的在前。
     */
    fun reloadTargets(instances: List<UELinkClient.Instance>): List<UELinkClient.Instance> =
        instances.filter { it.hasHttp && it.kind != UELinkClient.KIND_BACKGROUND }
            .sortedWith(compareBy<UELinkClient.Instance> { if (it.kind == UELinkClient.KIND_EDITOR) 0 else 1 }.thenByDescending { it.startedAt })

    data class Item(val module: String, val ok: Boolean, val mode: String? = null, val error: String? = null)

    data class Reply(val items: List<Item>, val note: String?)

    /** `{"items": [{"module", "ok", "mode", "error"?}], "note"?}`（协议 lua.reload）。字段缺失时尽量宽容。 */
    fun parseReloadReply(json: JsonObject): Reply {
        val items = json.get("items")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val module = o.get("module")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val error = o.get("error")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            val ok = o.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: (error == null)
            Item(module, ok, o.get("mode")?.takeIf { it.isJsonPrimitive }?.asString, error)
        }.orEmpty()
        return Reply(items, json.get("note")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() })
    }
}

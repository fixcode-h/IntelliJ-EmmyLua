package com.fixcode.intellij.lua.project

import com.google.gson.JsonObject

/**
 * 把 UE 编辑器 `luadecl.status` 的结果翻成底部状态栏的进度（像 IDEA 自己的索引那样）。
 * 纯函数，不碰 IDE，可以直接单测。
 *
 * UELink 的阶段：native（C++ 反射类型）→ assets（蓝图等资源）；补全蓝图时另有 loadDone / loadTotal（每帧加载一个）。
 * 两边都没活了才算完成。
 */
object UnrealDeclProgress {

    data class Progress(val text: String, val fraction: Double?)

    /** 还在生成时返回进度；空闲（且没有待加载的蓝图）时返回 null。 */
    fun of(status: JsonObject): Progress? {
        val phase = status.str("phase") ?: "idle"
        val done = status.int("done")
        val total = status.int("total")
        val loadDone = status.int("loadDone")
        val loadTotal = status.int("loadTotal")
        val loading = loadTotal != null && loadTotal > 0 && (loadDone ?: 0) < loadTotal
        return when {
            phase == "native" -> step("生成 C++ 反射类型的声明", done, total)
            phase == "assets" -> step("生成蓝图和资源的声明", done, total)
            phase == "loading" || loading -> step("加载没打开过的蓝图，补全成员", loadDone, loadTotal)
            else -> null
        }
    }

    /** 完成时的通知文本：一共多少文件，这一次写了 / 删了多少，用了多久。 */
    fun summary(status: JsonObject): String {
        val parts = mutableListOf<String>()
        status.int("files")?.let { parts += "共 $it 个声明文件" }
        val written = status.int("written") ?: 0
        val deleted = status.int("deleted") ?: 0
        parts += if (written == 0 && deleted == 0) "没有变化" else "这次写了 $written 个、删了 $deleted 个"
        status.int("lastPassMs")?.let { parts += "用时 %.1f 秒".format(it / 1000.0) }
        status.int("stubs")?.takeIf { it > 0 }?.let { parts += "$it 个蓝图只有类名（没加载过，可用「补全全部蓝图」）" }
        return "UE 反射声明已更新：" + parts.joinToString("，")
    }

    private fun step(label: String, done: Int?, total: Int?): Progress {
        if (total == null || total <= 0) return Progress(label, null)
        val d = (done ?: 0).coerceIn(0, total)
        return Progress("$label  $d / $total", d.toDouble() / total)
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
}

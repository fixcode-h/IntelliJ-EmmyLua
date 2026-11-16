package com.fixcode.intellij.lua.debug.engine

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/**
 * 显示规则文件（variable-view.md 11.1，默认 `.emmylua/debug-view.json`）的读写。
 * 变量树右键「固定到顶部 / 隐藏此字段」改的就是这里的 `types.<类名>.pin / hide`；
 * 只动这两个数组，文件里别的内容（summary、ueProperties、用户自己加的字段）原样保留。
 */
object ViewRules {
    const val VERSION = 1

    /** 解析文件内容；空文本当作空规则。不是 JSON 对象时抛 [IllegalArgumentException]（不覆盖用户的文件）。 */
    fun parse(text: String?): JsonObject {
        if (text.isNullOrBlank()) return JsonObject().apply { addProperty("version", VERSION) }
        // 严格模式：带注释、尾逗号的文件不是我们写得回去的格式，宁可报错也不吞掉用户的注释
        val element = try {
            val reader = JsonReader(StringReader(text)).apply { isLenient = false }
            val parsed = Gson().getAdapter(JsonElement::class.java).read(reader)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "末尾有多余内容" }
            parsed
        } catch (e: Exception) {
            throw IllegalArgumentException("显示规则文件不是合法的 JSON：${e.message}", e)
        }
        require(element.isJsonObject) { "显示规则文件的最外层必须是 JSON 对象" }
        return element.asJsonObject
    }

    fun format(rules: JsonObject): String = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(rules) + "\n"

    fun isPinned(rules: JsonObject, type: String, field: String): Boolean = list(rules, type, "pin").contains(field)

    fun isHidden(rules: JsonObject, type: String, field: String): Boolean = list(rules, type, "hide").contains(field)

    /** 固定 / 取消固定。固定时同时从 hide 里拿掉（两者互斥）。返回改后是否固定。 */
    fun togglePin(rules: JsonObject, type: String, field: String): Boolean {
        val pinned = !isPinned(rules, type, field)
        if (pinned) {
            remove(rules, type, "hide", field)
            add(rules, type, "pin", field)
        } else {
            remove(rules, type, "pin", field)
        }
        return pinned
    }

    /** 隐藏 / 取消隐藏。隐藏时同时取消固定。返回改后是否隐藏。 */
    fun toggleHide(rules: JsonObject, type: String, field: String): Boolean {
        val hidden = !isHidden(rules, type, field)
        if (hidden) {
            remove(rules, type, "pin", field)
            add(rules, type, "hide", field)
        } else {
            remove(rules, type, "hide", field)
        }
        return hidden
    }

    private fun list(rules: JsonObject, type: String, key: String): List<String> {
        val array = rules.obj("types")?.obj(type)?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
    }

    private fun add(rules: JsonObject, type: String, key: String, field: String) {
        if (!rules.has("version")) rules.addProperty("version", VERSION)
        val types = rules.obj("types") ?: JsonObject().also { rules.add("types", it) }
        val entry = types.obj(type) ?: JsonObject().also { types.add(type, it) }
        val array = entry.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { entry.add(key, it) }
        if (array.none { it.isJsonPrimitive && it.asString == field }) array.add(JsonPrimitive(field))
    }

    private fun remove(rules: JsonObject, type: String, key: String, field: String) {
        val types = rules.obj("types") ?: return
        val entry = types.obj(type) ?: return
        val array = entry.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        val kept = JsonArray()
        array.forEach { if (!(it.isJsonPrimitive && it.asString == field)) kept.add(it) }
        if (kept.size() == 0) entry.remove(key) else entry.add(key, kept)
        // 只剩空对象的类型也删掉，文件不留垃圾
        if (entry.size() == 0) types.remove(type)
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
}

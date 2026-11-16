package com.fixcode.intellij.lua.debugger.mcp.json

/**
 * 极简 JSON：解析成 Kotlin 原生值（LinkedHashMap / List / String / Long / Double / Boolean / null），
 * 写回时保留键顺序、可指定缩进。
 *
 * 不引第三方库：这个模块要能脱离 IntelliJ 单测，安装器还要"跟随原文件缩进"写回，
 * 自己控制输出格式比配 Gson 的 pretty printer 简单。
 * 严格模式：不接受注释和尾逗号。带注释的配置文件（JSONC）解析失败，安装器会改为提示手动粘贴，
 * 不会把用户的注释吃掉。
 */
object Json {
    class ParseException(message: String, val offset: Int) : RuntimeException("$message（位置 $offset）")

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("JSON 末尾有多余内容", p.pos)
        return v
    }

    fun parseObject(text: String): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return parse(text) as? MutableMap<String, Any?> ?: throw ParseException("顶层不是 JSON 对象", 0)
    }

    /** 紧凑输出，给 MCP 报文用。 */
    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value, null, 0) }.toString()

    /** 带缩进输出，给配置文件用；[indent] 例如两个空格、四个空格或 "\t"。 */
    fun pretty(value: Any?, indent: String = "  "): String =
        StringBuilder().also { writeTo(it, value, indent, 0) }.toString()

    /** 从已有文件猜缩进：取第一行以空白开头的缩进；猜不出用两个空格。 */
    fun detectIndent(text: String): String {
        for (line in text.lineSequence()) {
            val ws = line.takeWhile { it == ' ' || it == '\t' }
            if (ws.isNotEmpty() && ws.length < line.length) return ws
        }
        return "  "
    }

    private fun writeTo(sb: StringBuilder, value: Any?, indent: String?, level: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(value)
            is Double, is Float -> {
                val d = (value as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong())
                else sb.append(d)
            }
            is Number -> sb.append(value)
            is Enum<*> -> quote(sb, value.name.lowercase())
            is Map<*, *> -> {
                if (value.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, level + 1)
                    quote(sb, k.toString())
                    sb.append(if (indent != null) ": " else ":")
                    writeTo(sb, v, indent, level + 1)
                }
                newline(sb, indent, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = value.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                list.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    newline(sb, indent, level + 1)
                    writeTo(sb, v, indent, level + 1)
                }
                newline(sb, indent, level)
                sb.append(']')
            }
            is Array<*> -> writeTo(sb, value.toList(), indent, level)
            else -> quote(sb, value.toString())
        }
    }

    private fun newline(sb: StringBuilder, indent: String?, level: Int) {
        if (indent == null) return
        sb.append('\n')
        repeat(level) { sb.append(indent) }
    }

    fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '﻿')) pos++
        }

        fun readValue(): Any? {
            if (atEnd()) throw ParseException("JSON 不完整", pos)
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '/' -> throw ParseException("不支持注释", pos)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw ParseException("意外的字符 '$c'", pos)
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("意外的内容", pos)
            pos += word.length
            return value
        }

        private fun readObject(): MutableMap<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("对象的键必须是字符串", pos)
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                map[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> { pos++; skipWs(); if (peek() == '}') throw ParseException("不支持尾逗号", pos) }
                    '}' -> { pos++; return map }
                    else -> throw ParseException("缺少 ',' 或 '}'", pos)
                }
            }
        }

        private fun readArray(): MutableList<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                skipWs()
                list.add(readValue())
                skipWs()
                when (peek()) {
                    ',' -> { pos++; skipWs(); if (peek() == ']') throw ParseException("不支持尾逗号", pos) }
                    ']' -> { pos++; return list }
                    else -> throw ParseException("缺少 ',' 或 ']'", pos)
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("字符串没有结束", pos)
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw ParseException("字符串没有结束", pos)
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("\\u 转义不完整", pos)
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar()
                                    ?: throw ParseException("\\u 转义无效", pos))
                                pos += 4
                            }
                            else -> throw ParseException("无效转义 \\$e", pos)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return if (text.any { it == '.' || it == 'e' || it == 'E' }) {
                text.toDoubleOrNull() ?: throw ParseException("无效数字 $text", start)
            } else {
                text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw ParseException("无效数字 $text", start)
            }
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[pos]

        private fun expect(c: Char) {
            if (peek() != c) throw ParseException("缺少 '$c'", pos)
            pos++
        }
    }
}

/**
 * 读 MCP 参数（规范见 AGENTS.md「MCP 工具规范」，照 UEToVS 的 ToolArgs）：
 * 未知参数名直接报错并列出合法的名字（拼错的过滤条件被悄悄忽略比报错更糟）；
 * 类型不对、超范围给出能照着改的错误，错误码 invalid_argument。
 */
class JsonArgs(val raw: Map<String, Any?>, allowed: Collection<String>? = null) {
    init {
        if (allowed != null) {
            val unknown = raw.keys.filter { it !in allowed }
            if (unknown.isNotEmpty()) {
                throw ToolArgumentException(
                    "未知参数：${unknown.joinToString()}。可用：${allowed.sorted().joinToString()}"
                )
            }
        }
    }

    fun has(name: String) = raw[name] != null

    fun string(name: String, maxLength: Int = 4096): String? {
        val s = when (val v = raw[name]) {
            null -> return null
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> throw ToolArgumentException("'$name' 应为字符串")
        }
        if (s.length > maxLength) throw ToolArgumentException("'$name' 超过 $maxLength 个字符")
        return s
    }

    fun requireString(name: String): String =
        string(name)?.takeIf { it.isNotBlank() } ?: throw ToolArgumentException("缺少参数 '$name'")

    fun int(name: String, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int? {
        val value = when (val v = raw[name]) {
            null -> return null
            is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong() else throw ToolArgumentException("'$name' 应为整数")
            is String -> v.trim().toLongOrNull() ?: throw ToolArgumentException("'$name' 应为整数") // 宽松："20"
            else -> throw ToolArgumentException("'$name' 应为整数")
        }
        if (value < min || value > max) throw ToolArgumentException("'$name' 应在 $min 到 $max 之间（实际 $value）")
        return value.toInt()
    }

    fun int(name: String, default: Int, min: Int, max: Int): Int = int(name, min, max) ?: default

    fun requireInt(name: String, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int =
        int(name, min, max) ?: throw ToolArgumentException("缺少参数 '$name'")

    fun bool(name: String): Boolean? = when (val v = raw[name]) {
        null -> null
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true" -> true; "false" -> false
            else -> throw ToolArgumentException("'$name' 应为 true 或 false")
        }
        else -> throw ToolArgumentException("'$name' 应为 true 或 false")
    }

    fun bool(name: String, default: Boolean): Boolean = bool(name) ?: default

    /** 字符串数组；单个字符串按逗号拆开也接受。 */
    fun stringList(name: String, maxItems: Int = 32): List<String> {
        val list = when (val v = raw[name]) {
            null -> return emptyList()
            is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            is List<*> -> v.map { (it as? String)?.trim() ?: throw ToolArgumentException("'$name' 应为字符串数组") }.filter { it.isNotEmpty() }
            else -> throw ToolArgumentException("'$name' 应为字符串数组")
        }
        if (list.size > maxItems) throw ToolArgumentException("'$name' 超过 $maxItems 项")
        return list
    }

    fun intList(name: String, maxItems: Int = 64): List<Int> = when (val v = raw[name]) {
        null -> emptyList()
        is List<*> -> v.map { (it as? Number)?.toInt() ?: throw ToolArgumentException("'$name' 应为整数数组") }
            .also { if (it.size > maxItems) throw ToolArgumentException("'$name' 超过 $maxItems 项") }
        else -> throw ToolArgumentException("'$name' 应为整数数组")
    }

    /** [allowed] 之一（不区分大小写），返回 [allowed] 里的原样写法。 */
    fun choice(name: String, default: String?, vararg allowed: String): String? {
        val s = string(name)?.trim() ?: return default
        return allowed.firstOrNull { it.equals(s, ignoreCase = true) }
            ?: throw ToolArgumentException("'$name' 只能是 ${allowed.joinToString(", ")}（实际 '$s'）")
    }

    /** 日志用的一行摘要（字符串截到 120 字）。 */
    fun summary(): String = raw.entries.sortedBy { it.key }.joinToString(" ") { (k, v) ->
        val s = if (v is String) v else Json.write(v)
        k + "=" + (if (s.length > 120) s.take(120) + "…" else s).replace('\n', ' ')
    }
}

/** 工具错误：作为 `isError: true` + `{"error": code, "message": ...}` 返回给 Agent，而不是协议错误。 */
open class ToolException(val code: String, message: String) : RuntimeException(message)

/** 参数错误（invalid_argument）。 */
class ToolArgumentException(message: String) : ToolException("invalid_argument", message)

package com.fixcode.intellij.lua.debugger.mcp.json

/**
 * 带注释、尾逗号的 JSON（JSONC，VS Code 的 `.vscode/mcp.json`、Gemini 的 settings.json 都允许）的读和「按结构改一项」。
 *
 * 为什么不解析后整份写回：那样注释会丢、用户的排版会变。这里只定位要改的那一个成员在原文里的位置，
 * 换掉它的值、在对象末尾插一个成员或删掉一个成员，其余文字（注释、空行、缩进、键的顺序）一个字不动。
 *
 * 做法：先把注释换成等长的空白（换行保留），这样位置和原文一一对应，扫描时不用管注释；
 * 再把尾逗号也换成空白就能交给严格的 [Json] 解析出值。
 */
object Jsonc {
    /** 解析 JSONC 顶层对象；不是合法 JSONC 抛 [Json.ParseException]。 */
    fun parseObject(text: String): MutableMap<String, Any?> = Json.parseObject(blankTrailingCommas(blankComments(text)))

    /** 文本里有注释或尾逗号（严格 JSON 解析不了，但 JSONC 可以）。 */
    fun hasExtensions(text: String): Boolean {
        val blanked = blankComments(text)
        return blanked != text || blankTrailingCommas(blanked) != blanked
    }

    /**
     * 把 [path] 指向的对象里的成员 [key] 设成 [value]：已有就只换值，没有就加在对象末尾；路径上缺的对象一并建出来。
     * [indentUnit] 是新写的值里每层的缩进。路径上的某个值不是对象时抛 [Json.ParseException]。
     */
    fun setMember(text: String, path: List<String>, key: String, value: Any?, indentUnit: String): String {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val scan = Scanner(blankComments(text))
        var obj = scan.rootObject()
        for ((i, p) in path.withIndex()) {
            val m = obj.members.firstOrNull { it.key == p }
            if (m == null) {
                // 缺的那一段连同里面的成员一起插进去
                var nested: Any? = linkedMapOf(key to value)
                for (q in path.subList(i + 1, path.size).asReversed()) nested = linkedMapOf(q to nested)
                return insert(text, scan, obj, p, nested, indentUnit, eol)
            }
            obj = scan.objectAt(m.valueStart) ?: throw Json.ParseException("\"$p\" 不是对象", m.valueStart)
        }
        val m = obj.members.firstOrNull { it.key == key } ?: return insert(text, scan, obj, key, value, indentUnit, eol)
        val indent = lineIndent(scan.text, m.keyStart) ?: ""
        return text.substring(0, m.valueStart) + render(value, indentUnit, indent, eol) + text.substring(m.valueEnd)
    }

    /** 删掉 [path] 指向的对象里的成员 [key]；没有这个成员（或路径不通）原样返回。 */
    fun removeMember(text: String, path: List<String>, key: String): String {
        val scan = Scanner(blankComments(text))
        var obj = scan.rootObject()
        for (p in path) {
            val m = obj.members.firstOrNull { it.key == p } ?: return text
            obj = scan.objectAt(m.valueStart) ?: return text
        }
        val idx = obj.members.indexOfFirst { it.key == key }
        if (idx < 0) return text
        val m = obj.members[idx]
        val b = scan.text
        var start = m.keyStart
        var end = m.commaEnd ?: m.valueEnd
        if (m.commaEnd == null && idx > 0) {
            // 最后一个成员没有逗号：连前一个成员后面的逗号一起删，免得留下尾逗号
            val prev = obj.members[idx - 1]
            if (prev.commaEnd != null) start = prev.commaEnd - 1
        } else {
            // 独占一行时整行删掉（行首的缩进和行尾的换行），不留空行
            val ls = lineStartIfBlankBefore(b, start)
            val le = lineEndIfBlankAfter(b, end)
            if (ls != null && le != null) {
                start = ls
                end = le
            }
        }
        return text.substring(0, start) + text.substring(end)
    }

    private fun insert(text: String, scan: Scanner, obj: Obj, key: String, value: Any?, unit: String, eol: String): String {
        val b = scan.text
        val last = obj.members.lastOrNull()
        if (last == null) {
            val outer = indentOfLine(b, obj.open)
            val inner = outer + unit
            val member = Json.write(key) + ": " + render(value, unit, inner, eol)
            // 空对象 `{}` 或里面只有注释：成员写在 `{` 后面另起一行，`}` 留在原处
            val closeOnOwnLine = lineStartIfBlankBefore(b, obj.close) != null
            val tail = if (closeOnOwnLine) "" else eol + outer
            return text.substring(0, obj.open + 1) + eol + inner + member + tail + text.substring(obj.open + 1)
        }
        val indent = lineIndent(b, last.keyStart) ?: (indentOfLine(b, obj.open) + unit)
        val member = Json.write(key) + ": " + render(value, unit, indent, eol)
        if (last.commaEnd != null) {
            // 原来就有尾逗号：新成员也照这个习惯带上
            val at = lineEndIfBlankAfter(b, last.commaEnd)?.let { lineContentEnd(b, it) } ?: last.commaEnd
            return text.substring(0, at) + eol + indent + member + "," + text.substring(at)
        }
        // 插在最后一个值后面那一行的行尾：同一行的行尾注释留在原来那个成员上
        val lineEnd = lineContentEndAfter(b, last.valueEnd)
        val sameLine = lineEnd != null
        val at = lineEnd ?: last.valueEnd
        val head = text.substring(0, last.valueEnd) + "," + text.substring(last.valueEnd, at)
        val sep = if (sameLine) eol + indent else " "
        return head + sep + member + text.substring(at)
    }

    private fun render(value: Any?, unit: String, indent: String, eol: String): String =
        Json.pretty(value, unit).replace("\n", eol + indent)

    /** 位置 [pos] 所在行开头的空白；这一行在 [pos] 之前还有别的字符时返回 null。 */
    private fun lineIndent(b: String, pos: Int): String? {
        val ls = lineStartIfBlankBefore(b, pos) ?: return null
        return b.substring(ls, pos)
    }

    /** [pos] 所在行行首的空白。 */
    private fun indentOfLine(b: String, pos: Int): String {
        val ls = b.lastIndexOf('\n', pos - 1) + 1
        var i = ls
        while (i < b.length && (b[i] == ' ' || b[i] == '\t')) i++
        return b.substring(ls, i)
    }

    private fun lineStartIfBlankBefore(b: String, pos: Int): Int? {
        var i = pos
        while (i > 0 && (b[i - 1] == ' ' || b[i - 1] == '\t')) i--
        return if (i == 0 || b[i - 1] == '\n') i else null
    }

    /** [pos] 之后到行尾都是空白（注释已经是空白）时，返回下一行的开头；否则 null。 */
    private fun lineEndIfBlankAfter(b: String, pos: Int): Int? {
        var i = pos
        while (i < b.length && (b[i] == ' ' || b[i] == '\t' || b[i] == '\r')) i++
        return when {
            i == b.length -> i
            b[i] == '\n' -> i + 1
            else -> null
        }
    }

    /** 下一行开头 [nextLine] 往回退到上一行内容结束处（去掉换行）。 */
    private fun lineContentEnd(b: String, nextLine: Int): Int {
        var i = nextLine
        if (i > 0 && b[i - 1] == '\n') i--
        if (i > 0 && b[i - 1] == '\r') i--
        return i
    }

    /** [pos] 之后这一行没有别的值（只有空白和注释）时，返回这一行内容的结束处（换行符之前）；否则 null。 */
    private fun lineContentEndAfter(b: String, pos: Int): Int? {
        val next = lineEndIfBlankAfter(b, pos) ?: return null
        return if (next == b.length && (next == 0 || b[next - 1] != '\n')) next else lineContentEnd(b, next)
    }

    /** 注释换成等长空白（换行保留），字符串里的 `//` 不算。 */
    fun blankComments(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> i = skipString(text, i)
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n' && text[i] != '\r') sb.setCharAt(i++, ' ')
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    while (i < end) {
                        if (text[i] != '\n' && text[i] != '\r') sb.setCharAt(i, ' ')
                        i++
                    }
                }
                else -> i++
            }
        }
        return sb.toString()
    }

    /** 尾逗号（后面只隔着空白就是 `}` 或 `]`）换成空白；输入应已去掉注释。 */
    private fun blankTrailingCommas(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') { i = skipString(text, i); continue }
            if (c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) sb.setCharAt(i, ' ')
            }
            i++
        }
        return sb.toString()
    }

    /** 从开头的引号跳到字符串结束后的位置。 */
    private fun skipString(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return s.length
    }

    private class Member(val key: String, val keyStart: Int, val valueStart: Int, val valueEnd: Int, val commaEnd: Int?)

    private class Obj(val open: Int, val close: Int, val members: List<Member>)

    /** 在去掉注释的文本上找对象和成员的位置；尾逗号照收。 */
    private class Scanner(val text: String) {
        fun rootObject(): Obj {
            val i = skipWs(0)
            return objectAt(i) ?: throw Json.ParseException("顶层不是 JSON 对象", i)
        }

        fun objectAt(start: Int): Obj? {
            if (start >= text.length || text[start] != '{') return null
            val members = mutableListOf<Member>()
            var i = skipWs(start + 1)
            while (true) {
                if (i >= text.length) throw Json.ParseException("对象没有结束", start)
                if (text[i] == '}') return Obj(start, i, members)
                if (text[i] != '"') throw Json.ParseException("这里应是键名", i)
                val keyStart = i
                val keyEnd = skipString(text, i)
                @Suppress("UNCHECKED_CAST")
                val key = Json.parse(text.substring(keyStart, keyEnd)) as String
                i = skipWs(keyEnd)
                if (i >= text.length || text[i] != ':') throw Json.ParseException("这里应是冒号", i)
                val valueStart = skipWs(i + 1)
                val valueEnd = skipValue(valueStart)
                i = skipWs(valueEnd)
                var commaEnd: Int? = null
                if (i < text.length && text[i] == ',') {
                    commaEnd = i + 1
                    i = skipWs(i + 1)
                } else if (i < text.length && text[i] != '}') {
                    throw Json.ParseException("这里应是逗号或 }", i)
                }
                members += Member(key, keyStart, valueStart, valueEnd, commaEnd)
            }
        }

        private fun skipWs(from: Int): Int {
            var i = from
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }

        private fun skipValue(start: Int): Int {
            if (start >= text.length) throw Json.ParseException("缺少值", start)
            return when (text[start]) {
                '"' -> skipString(text, start)
                '{', '[' -> {
                    var depth = 0
                    var i = start
                    while (i < text.length) {
                        when (text[i]) {
                            '"' -> { i = skipString(text, i); continue }
                            '{', '[' -> depth++
                            '}', ']' -> if (--depth == 0) return i + 1
                        }
                        i++
                    }
                    throw Json.ParseException("括号没有配对", start)
                }
                else -> {
                    var i = start
                    while (i < text.length && text[i] != ',' && text[i] != '}' && text[i] != ']' && !text[i].isWhitespace()) i++
                    i
                }
            }
        }
    }
}

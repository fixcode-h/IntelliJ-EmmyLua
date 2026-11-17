package com.fixcode.intellij.lua.debugger.mcp.code

/**
 * 名字的模糊匹配，照 UEToVS 的 FuzzyMatcher（Rider / IntelliJ 的手感）：完全相同 > 前缀 > CamelHumps
 * （'OBC' → OnButtonClicked、'gAC' → GetActorComponent）> 子串；带 `*` 或空格时按通配。
 * 同一个查询建一次，逐个候选调 [match]。分数越大越好，只用来排序。
 */
class FuzzyMatcher(pattern: String) {
    private val pattern = pattern.trim()
    private val hasWildcard = '*' in this.pattern || ' ' in this.pattern
    private val fragments = this.pattern.split('*', ' ').filter { it.isNotEmpty() }

    val isEmpty: Boolean get() = fragments.isEmpty()

    /** 不匹配时返回 null。 */
    fun match(name: String): Int? {
        if (name.isEmpty() || isEmpty) return null
        return if (hasWildcard) matchWildcard(name) else matchSingle(pattern, name)
    }

    private fun matchSingle(p: String, name: String): Int? {
        val len = name.length
        if (p.equals(name, ignoreCase = true)) return EXACT + if (p == name) 100 else 0
        if (p.length < len && regionMatches(p, name, 0)) {
            return PREFIX + (if (name.startsWith(p)) 100 else 0) - minOf(len, 500)
        }
        val camel = camelHumps(p, name, 0)
        if (camel != null) {
            // 片段越少（连续字符越多）越好
            return CAMEL - camel.runs * 200 - minOf(len, 500) - camel.start * 10
        }
        val idx = indexOf(p, name, 0)
        if (idx >= 0) return SUBSTRING - idx * 10 - minOf(len, 500) + if (isWordStart(name, idx)) 2000 else 0
        return null
    }

    private fun matchWildcard(name: String): Int? {
        var pos = 0
        var runs = 0
        for ((f, frag) in fragments.withIndex()) {
            if (f == 0 && pattern[0] != '*' && pattern[0] != ' ') {
                // 第一段要从词首开始（允许 CamelHumps）
                val head = camelHumps(frag, name, 0)
                if (head != null) {
                    runs += head.runs; pos = head.end
                } else {
                    val i = indexOf(frag, name, 0)
                    if (i < 0) return null
                    runs++; pos = i + frag.length
                }
                continue
            }
            val part = camelHumps(frag, name, pos)
            if (part != null) {
                runs += part.runs; pos = part.end
                continue
            }
            val i = indexOf(frag, name, pos)
            if (i < 0) return null
            runs++; pos = i + frag.length
        }
        return WILDCARD - runs * 100 - minOf(name.length, 500)
    }

    private class Humps(val start: Int, val end: Int, val runs: Int)

    /** 模式的每个字符要么接着上一个匹配的字符，要么落在 [name] 的词首；第一个字符必须在词首。 */
    private fun camelHumps(p: String, name: String, from: Int): Humps? {
        if (p.isEmpty() || p.length > name.length - from) return null
        for (start in from until name.length) {
            if (!isWordStart(name, start) || !charEq(p[0], name[start])) continue
            val positions = IntArray(p.length)
            positions[0] = start
            if (extend(p, name, 1, start, positions)) {
                var runs = 1
                for (i in 1 until positions.size) if (positions[i] != positions[i - 1] + 1) runs++
                return Humps(start, positions.last() + 1, runs)
            }
        }
        return null
    }

    private fun extend(p: String, name: String, pi: Int, lastNi: Int, positions: IntArray): Boolean {
        if (pi == p.length) return true
        val pc = p[pi]
        val next = lastNi + 1
        // 优先接着当前这一段
        if (next < name.length && charEq(pc, name[next])) {
            positions[pi] = next
            if (extend(p, name, pi + 1, next, positions)) return true
        }
        // 否则跳到后面的词首
        for (ni in lastNi + 2 until name.length) {
            if (!isWordStart(name, ni) || !charEq(pc, name[ni])) continue
            positions[pi] = ni
            if (extend(p, name, pi + 1, ni, positions)) return true
        }
        return false
    }

    companion object {
        private const val EXACT = 100000
        private const val PREFIX = 80000
        private const val CAMEL = 60000
        private const val SUBSTRING = 30000
        private const val WILDCARD = 20000

        private fun regionMatches(p: String, name: String, at: Int): Boolean {
            if (at + p.length > name.length) return false
            for (i in p.indices) if (!charEq(p[i], name[at + i])) return false
            return true
        }

        private fun indexOf(p: String, name: String, from: Int): Int {
            var i = from
            while (i + p.length <= name.length) {
                if (regionMatches(p, name, i)) return i
                i++
            }
            return -1
        }

        /** 模式里的大写字母只配大写（词首）；小写配大小写都行。 */
        private fun charEq(p: Char, n: Char): Boolean {
            if (p == n) return true
            return (p.lowercaseChar() == n.lowercaseChar() && !p.isUpperCase()) || p.uppercaseChar() == n
        }

        fun isWordStart(s: String, i: Int): Boolean {
            if (i == 0) return true
            val c = s[i]
            val prev = s[i - 1]
            if (!c.isLetterOrDigit()) return false
            if (!prev.isLetterOrDigit()) return true // _ . : / 之后
            if (c.isUpperCase() && prev.isLowerCase()) return true
            if (c.isDigit() && !prev.isDigit()) return true
            if (c.isLetter() && prev.isDigit()) return true
            // HTTPServer：S 后面是小写、前面是大写，也算词首
            return c.isUpperCase() && prev.isUpperCase() && i + 1 < s.length && s[i + 1].isLowerCase()
        }
    }
}

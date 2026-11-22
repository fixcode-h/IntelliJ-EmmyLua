package com.fixcode.intellij.lua.debug.xdebugger

import com.fixcode.intellij.lua.lexer.LuaLexer
import com.fixcode.intellij.lua.lexer.LuaTokenTypes
import com.intellij.openapi.editor.Document
import java.util.Collections
import java.util.WeakHashMap

/**
 * 文档里跨行的长注释和字符串的位置，给行尾变量值判断「这一行开头是不是在注释 / 字符串中间」。
 *
 * 绘制线程里平台软禁止拿锁，PSI 读不了，所以不走扁平 PSI：直接用词法器扫文档的不可变文本，
 * 按文档修改戳缓存，文档不变时每次绘制只做一次二分查找。
 */
internal object LuaMultilineSpans {
    private class Spans(val stamp: Long, val ranges: IntArray)

    private val cache = Collections.synchronizedMap(WeakHashMap<Document, Spans>())

    /** 覆盖 [offset] 且从它前面开始的跨行 token 的结束位置；没有返回 -1。 */
    fun endOfSpanCovering(document: Document, offset: Int): Int = endOfSpanCovering(rangesOf(document), offset)

    /** [ranges] 是 [scan] 的结果：按起点排好的 `[start, end)` 对。 */
    internal fun endOfSpanCovering(ranges: IntArray, offset: Int): Int {
        var lo = 0
        var hi = ranges.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = ranges[mid * 2]
            val end = ranges[mid * 2 + 1]
            when {
                end <= offset -> lo = mid + 1
                start >= offset -> hi = mid - 1
                else -> return end
            }
        }
        return -1
    }

    private fun rangesOf(document: Document): IntArray {
        val stamp = document.modificationStamp
        cache[document]?.takeIf { it.stamp == stamp }?.let { return it.ranges }
        val ranges = scan(document.immutableCharSequence)
        cache[document] = Spans(stamp, ranges)
        return ranges
    }

    internal fun scan(text: CharSequence): IntArray {
        val out = ArrayList<Int>()
        val lexer = LuaLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type == LuaTokenTypes.BLOCK_COMMENT || LuaTokenTypes.STRINGS.contains(type)) {
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                if (containsLineBreak(text, start, end)) {
                    out.add(start)
                    out.add(end)
                }
            }
            lexer.advance()
        }
        return out.toIntArray()
    }

    private fun containsLineBreak(text: CharSequence, start: Int, end: Int): Boolean {
        for (i in start until end) if (text[i] == '\n' || text[i] == '\r') return true
        return false
    }
}

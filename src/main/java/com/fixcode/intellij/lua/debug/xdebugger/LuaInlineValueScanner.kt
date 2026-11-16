package com.fixcode.intellij.lua.debug.xdebugger

import com.fixcode.intellij.lua.lexer.LuaLexer
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.psi.tree.IElementType

/**
 * 行内变量值：从一行源码里挑出要显示的变量（[LuaInlineValuePainter] 用）。
 *
 * 为什么不用语法树：语言支持迁到 LSP 后 IDE 里只剩扁平 PSI（设计文档 §4）。这里只用词法器切本行的 token
 * （跳过空白和注释），再按 Lua 语句的几种开头做一个小的手写扫描，不等索引、不碰 IntelliJ 的编辑器和 PSI，
 * 可以直接单测。
 *
 * 规则（与旧 PSI 实现一致）：
 * 1. `local a, b = x, 1`：显示 LHS 名字；RHS 对应位置若是纯路径（`a.b.c`）作为备选路径，若是字面量作为兜底文本。
 * 2. `a.b = y`：LHS 是纯路径时显示末段（`self.x` → `x`），单独的 `self` 不显示；RHS 规则同上。
 * 3. `for i = ...`、`for k, v in ...`：显示循环变量。
 * 4. `function foo(a, b)`（含 `local function`、匿名函数）：显示参数。
 * 5. 单个 LHS = 单个 table 字面量（`local t = { k = v }`）时，本行的字段 `k = v` 也显示：
 *    值是字面量就直接显示字面量，是纯路径就按路径取值。多行 table 里只有字段的那一行（`k = v,`）同样按字段处理。
 * 6. 本行所有调用的实参里的纯路径（`foo(self.x, y)` → `x`、`y`）。
 * 7. 以上都没有时兜底：收集不在 `.`/`:` 后面的名字（`foo()` → `foo`，`a.b` → `b`）。
 * 最后按 displayName + 路径（或字面量）去重，保持首次出现顺序。
 */
object LuaInlineValueScanner {

    /**
     * 行内一个待绘制条目。
     *
     * - [displayName]：展示用的短名（e.g. `isAble`）
     * - [pathSegments]：从 frame 顶层变量起的访问链（e.g. `[self, x, isAble]`）
     *   当 pathSegments 为 null 时，使用 [literalText] 直接渲染（适配 table 字面量里的字面值字段）
     * - [fallbackPath]：可选的备选路径。首选路径（pathSegments）取不到时再尝试这个。
     *   典型场景：赋值行 `self.x = y`，首选 LHS `self.x`（展示当前值），
     *   如果 LHS 不存在则回退到 RHS `y`（展示即将写入的值）。
     */
    data class Entry(
        val displayName: String,
        val pathSegments: List<String>?,
        val literalText: String? = null,
        val fallbackPath: List<String>? = null
    )

    fun scan(line: CharSequence): List<Entry> = Scan(tokenize(line)).run()

    private class Tok(val type: IElementType, val text: String)

    private fun tokenize(line: CharSequence): List<Tok> {
        val lexer = LuaLexer()
        lexer.start(line, 0, line.length, LuaLexer.STATE_NORMAL)
        val out = ArrayList<Tok>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != T.WHITE_SPACE && !T.COMMENTS.contains(type)) {
                out += Tok(type, line.subSequence(lexer.tokenStart, lexer.tokenEnd).toString())
            }
            lexer.advance()
        }
        return out
    }

    /** 一次扫描的状态：token 列表、已产出的条目、已经归给某条语句的 token（兜底时不再收集）。 */
    private class Scan(private val toks: List<Tok>) {
        private val n = toks.size
        private val out = ArrayList<Entry>()
        private val seenParams = HashSet<String>()
        /** 函数定义的参数括号：调用实参扫描时跳过。 */
        private val defParens = HashSet<Int>()
        /** 属于 local / 赋值语句或函数名的 token：兜底时排除，和旧实现排除「已处理语句」一致。 */
        private val handled = BooleanArray(n)

        fun run(): List<Entry> {
            var i = 0
            while (i < n) i = parseBlock(i, untilEnd = false)
            collectCallArgs()
            if (out.isEmpty()) collectFallback()
            return dedup(out)
        }

        // ---------- 语句 ----------

        /**
         * 扫语句直到行尾；[untilEnd] 为 true 时（匿名函数体）遇到配对的 `end` 返回其后位置。
         * 一行可能从语句中间开始（多行表达式、多行 table），认不出的 token 直接跳过。
         */
        fun parseBlock(start: Int, untilEnd: Boolean): Int {
            var i = start
            var depth = 0
            while (i < n) {
                val t = toks[i]
                when (t.type) {
                    T.KEYWORD -> when (t.text) {
                        "local" -> i = parseLocal(i)
                        "function" -> i = parseFunction(i)
                        "for" -> i = parseFor(i)
                        "if" -> { depth++; i = skipExprList(i + 1) }
                        "elseif", "while", "return" -> i = skipExprList(i + 1)
                        "until" -> { depth--; i = skipExprList(i + 1) }
                        "do", "repeat" -> { depth++; i++ }
                        "end" -> {
                            if (untilEnd && depth == 0) return i + 1
                            depth--
                            i++
                        }
                        "goto" -> i += 2
                        else -> i++
                    }
                    T.DOUBLE_COLON -> i += 3 // ::label::
                    T.NAME, T.SELF, T.LPAREN -> i = parseExprStatOrAssign(i)
                    else -> i++
                }
            }
            return n
        }

        private fun parseLocal(start: Int): Int {
            var k = start + 1
            if (isKeyword(k, "function")) {
                markHandled(start, start + 1)
                return parseFunction(k)
            }
            val names = ArrayList<String>()
            while (k < n && toks[k].type == T.NAME) {
                names += toks[k].text
                k++
                // Lua 5.4 属性：local x <const> = 1
                if (isOp(k, "<") && type(k + 1) == T.NAME && isOp(k + 2, ">")) k += 3
                if (type(k) == T.COMMA) k++ else break
            }
            val rhs = ArrayList<IntRange>()
            // RHS 里的匿名函数会先产出参数条目；LHS 的条目插到它们前面，保持从左到右的顺序
            val insertAt = out.size
            if (type(k) == T.ASSIGN) k = parseExprList(k + 1, rhs)
            markHandled(start, k)

            // 取值优先 LHS，取不到再回退 RHS 路径 / 字面量：断点停在「本行执行前」，LHS 可能还没赋值，
            // 而 RHS 通常已经是可见变量。
            out.addAll(insertAt, names.withIndex()
                .filter { isDisplayableName(it.value) }
                .map { (idx, name) -> assignEntry(name, listOf(name), rhs.getOrNull(idx)) })
            // 单个 LHS = 单个 table 字面量：展开本行的字段
            if (names.size == 1 && rhs.size == 1 && isDisplayableName(names[0])) expandTable(rhs[0])
            return k
        }

        /** `function a.b:c(x, y)` / 匿名 `function(x)`：参数本身就是 frame 顶层变量，直接按名字取值。 */
        fun parseFunction(start: Int): Int {
            var k = start + 1
            val nameStart = k
            if (type(k) == T.NAME || type(k) == T.SELF) {
                k++
                while ((type(k) == T.DOT || type(k) == T.COLON) && (type(k + 1) == T.NAME || type(k + 1) == T.SELF)) k += 2
            }
            markHandled(nameStart, k)
            if (type(k) != T.LPAREN) return k
            defParens += k
            k++
            while (k < n && toks[k].type != T.RPAREN) {
                val t = toks[k]
                if (t.type == T.NAME && isDisplayableName(t.text) && seenParams.add(t.text)) {
                    out += Entry(t.text, listOf(t.text))
                }
                k++
            }
            if (k < n) k++
            // 函数体：同一行里的语句照常扫（单行函数、匿名函数）
            return parseBlock(k, untilEnd = true)
        }

        /** `for i = 1, n do` / `for k, v in pairs(t) do`：循环变量同参数处理。 */
        private fun parseFor(start: Int): Int {
            var k = start + 1
            while (type(k) == T.NAME) {
                val name = toks[k].text
                if (isDisplayableName(name) && seenParams.add(name)) out += Entry(name, listOf(name))
                k++
                if (type(k) == T.COMMA) k++ else break
            }
            if (type(k) == T.ASSIGN || isKeyword(k, "in")) k = skipExprList(k + 1)
            return k
        }

        /** 赋值或表达式语句（调用）。 */
        private fun parseExprStatOrAssign(start: Int): Int {
            val lhs = ArrayList<IntRange>()
            var k = start
            while (true) {
                val e = skipExpr(k)
                if (e == k) break
                lhs += k until e
                if (type(e) == T.COMMA) k = e + 1 else { k = e; break }
            }
            if (type(k) != T.ASSIGN) return maxOf(k, start + 1)

            val rhs = ArrayList<IntRange>()
            val insertAt = out.size
            var fieldMode = false
            var j = k + 1
            while (true) {
                val e = skipExpr(j)
                if (e > j) rhs += j until e
                if (type(e) != T.COMMA) { j = e; break }
                j = e + 1
                // `a = 1, b = 2`、行尾的 `,`：赋值语句不会这样写，这是多行 table 里的字段行
                if (j >= n || type(j) == T.RCURLY || (type(j) == T.NAME && type(j + 1) == T.ASSIGN) || type(j) == T.LBRACK) {
                    fieldMode = true
                    break
                }
            }
            // 字段后面紧跟没配对的 `}`：同样是 table 的最后一个字段
            if (type(j) == T.RCURLY) fieldMode = true

            val single = lhs.singleOrNull()
            if (fieldMode && single != null && single.first == single.last && toks[single.first].type == T.NAME) {
                return parseTableFields(start)
            }

            markHandled(start, j)
            val lhsEntries = lhs.withIndex().mapNotNull { (idx, range) ->
                val path = purePath(range, allowColon = false)
                // self 本身不展示
                if (path == null || (path.size == 1 && path[0] == "self")) null
                else assignEntry(path.last(), path, rhs.getOrNull(idx))
            }
            out.addAll(insertAt, lhsEntries)
            if (lhs.size == 1 && rhs.size == 1 && purePath(lhs[0], allowColon = false) != null) expandTable(rhs[0])
            return maxOf(j, start + 1)
        }

        /**
         * 赋值行的条目：首选 LHS 路径（反映执行前的当前值，循环里第二次进入、字段已存在时能直接查到）；
         * LHS 取不到（首次赋值）时回退 RHS 路径；RHS 是字面量时附带字面量文本作最后兜底。
         */
        private fun assignEntry(displayName: String, lhsPath: List<String>, rhs: IntRange?): Entry {
            val fallback = rhs?.let { purePath(it, allowColon = false) }
            val literal = rhs?.let { literal(it) }
            return Entry(displayName, lhsPath, literalText = literal, fallbackPath = fallback)
        }

        /** RHS 是 `{ ... }` 时展开本行里它的直接字段。 */
        private fun expandTable(range: IntRange) {
            if (toks[range.first].type != T.LCURLY) return
            // 整个表达式必须就是这个 table（`{...}` 或行尾未闭合的 `{ ...`），不是 `{...}.x` 之类
            val close = matchClose(range.first)
            if (close < n && close != range.last) return
            parseTableFields(range.first + 1)
        }

        /**
         * table 构造器里的字段，直到没配对的 `}` 或行尾，返回停下的位置。
         * - `k = 字面量` → 显示字面量文本
         * - `k = 纯路径` → 按 RHS 路径取值（不取 k：k 不是 frame 里的变量）
         * - 嵌套 table、函数、运算等 → 不显示（table 一律留给 Variables 面板）
         */
        private fun parseTableFields(start: Int): Int {
            var k = start
            while (k < n) {
                when {
                    toks[k].type == T.RCURLY -> return k
                    toks[k].type == T.COMMA || toks[k].type == T.SEMI -> k++
                    toks[k].type == T.NAME && type(k + 1) == T.ASSIGN -> {
                        val name = toks[k].text
                        val e = skipExpr(k + 2)
                        markHandled(k, e)
                        if (e > k + 2 && isDisplayableName(name)) fieldEntry(name, k + 2 until e)?.let { out += it }
                        k = maxOf(e, k + 2)
                    }
                    toks[k].type == T.LBRACK -> {
                        // [expr] = v：键不是名字，不显示，跳过整个字段
                        var e = minOf(matchClose(k) + 1, n)
                        if (type(e) == T.ASSIGN) e = skipExpr(e + 1)
                        k = maxOf(e, k + 1)
                    }
                    else -> k = maxOf(skipExpr(k), k + 1)
                }
            }
            return n
        }

        private fun fieldEntry(name: String, value: IntRange): Entry? {
            literal(value)?.let { return Entry(name, null, literalText = it) }
            val path = purePath(value, allowColon = false) ?: return null
            if (path.size == 1 && !isDisplayableName(path[0])) return null
            return Entry(name, path)
        }

        // ---------- 表达式 ----------

        private fun parseExprList(start: Int, into: MutableList<IntRange>): Int {
            var k = start
            while (true) {
                val e = skipExpr(k)
                if (e > k) into += k until e
                if (type(e) == T.COMMA) k = e + 1 else return e
            }
        }

        private fun skipExprList(start: Int): Int = parseExprList(start, ArrayList())

        /**
         * 跳过一个表达式，返回其后位置。在顶层遇到 `,` `;` `=`、没配对的右括号、语句关键字，
         * 或两个操作数相邻（`a = b c = d` 里的 `c`，说明前一条语句已经结束）时停下。
         * 途经的匿名函数照常扫参数和函数体。
         */
        private fun skipExpr(start: Int): Int {
            var j = start
            var depth = 0
            var prevOperand = false
            while (j < n) {
                val t = toks[j]
                if (depth == 0) {
                    when {
                        t.type == T.COMMA || t.type == T.SEMI || t.type == T.ASSIGN || t.type == T.DOUBLE_COLON -> return j
                        t.type == T.RPAREN || t.type == T.RBRACK || t.type == T.RCURLY -> return j
                        t.type == T.KEYWORD && t.text !in EXPR_KEYWORDS -> return j
                        prevOperand && startsNewOperand(t) -> return j
                    }
                }
                when (t.type) {
                    T.KEYWORD -> {
                        if (t.text == "function") {
                            j = parseFunction(j)
                            prevOperand = true
                            continue
                        }
                        prevOperand = false
                    }
                    T.LPAREN, T.LBRACK, T.LCURLY -> { depth++; prevOperand = false }
                    T.RPAREN, T.RBRACK, T.RCURLY -> { depth--; prevOperand = true }
                    T.NAME, T.SELF, T.NUMBER, T.STRING, T.LONG_STRING, T.CONSTANT, T.ELLIPSIS -> prevOperand = true
                    else -> prevOperand = false
                }
                j++
            }
            return n
        }

        /** 操作数后面再出现这些就是新语句；字符串、`{`、`(` 跟在后面是调用（`f "x"`、`f{}`、`f()`），不算。 */
        private fun startsNewOperand(t: Tok): Boolean = when (t.type) {
            T.NAME, T.SELF, T.NUMBER, T.CONSTANT, T.ELLIPSIS -> true
            T.KEYWORD -> t.text == "not" || t.text == "function"
            else -> false
        }

        /** 从 [open] 处的左括号找配对的右括号；行内没闭合返回 n。 */
        private fun matchClose(open: Int): Int {
            var depth = 0
            for (j in open until n) {
                when (toks[j].type) {
                    T.LPAREN, T.LBRACK, T.LCURLY -> depth++
                    T.RPAREN, T.RBRACK, T.RCURLY -> if (--depth == 0) return j
                }
            }
            return n
        }

        /**
         * 纯路径 `a`、`self.x.y`（[allowColon] 时也接受 `a:b`）。下标 `a[1]`、调用 `a()`、运算 → null。
         * 首段允许 `self`（`self.x.y`），单独的 `self` 由调用方决定是否显示。
         */
        fun purePath(range: IntRange, allowColon: Boolean): List<String>? {
            if (range.isEmpty() || range.last >= n) return null
            val first = toks[range.first]
            if (first.type != T.NAME && first.type != T.SELF) return null
            val path = arrayListOf(first.text)
            var k = range.first + 1
            while (k <= range.last) {
                val sep = toks[k].type
                if (sep != T.DOT && !(allowColon && sep == T.COLON)) return null
                if (k + 1 > range.last) return null
                val member = toks[k + 1]
                if (member.type != T.NAME || !isDisplayableName(member.text)) return null
                path += member.text
                k += 2
            }
            return path
        }

        /** 字面量：nil/true/false、数字（含负号）、字符串（含引号，和旧实现显示 PSI 文本一致）。 */
        private fun literal(range: IntRange): String? {
            if (range.isEmpty() || range.last >= n) return null
            val first = toks[range.first]
            if (range.first == range.last) {
                return when (first.type) {
                    T.CONSTANT, T.NUMBER, T.STRING, T.LONG_STRING -> first.text
                    else -> null
                }
            }
            if (range.last == range.first + 1 && first.type == T.OPERATOR && first.text == "-" && toks[range.last].type == T.NUMBER) {
                return "-" + toks[range.last].text
            }
            return null
        }

        // ---------- 调用实参、兜底 ----------

        /**
         * 本行所有调用的实参里能解析为纯路径的：`foo(x)` → x、`self:m(self.a.b, c)` → b 和 c。
         * 调用可能嵌在 local / 赋值的 RHS 里，照样扫（`local r = self:m(self.x)` 也显示 `x`），重复的最后去重。
         * 多行调用只看本行这部分：行尾未闭合时最后一个实参到行尾为止。
         */
        private fun collectCallArgs() {
            for (p in 0 until n) {
                if (toks[p].type != T.LPAREN || p in defParens || p == 0) continue
                when (toks[p - 1].type) {
                    T.NAME, T.SELF, T.RPAREN, T.RBRACK, T.STRING, T.LONG_STRING, T.RCURLY -> Unit
                    else -> continue
                }
                // `(a)` 前一个 token 是 `)`/`]` 时也可能是新语句开头的括号表达式，这里不细分，按调用处理
                var k = p + 1
                var segStart = k
                var depth = 0
                while (k <= n) {
                    val t = if (k < n) toks[k].type else null
                    if (depth == 0 && (t == null || t == T.COMMA || t == T.RPAREN || t == T.RBRACK || t == T.RCURLY)) {
                        callArgEntry(segStart until k)?.let { out += it }
                        if (t != T.COMMA) break
                        segStart = k + 1
                    } else when (t) {
                        T.LPAREN, T.LBRACK, T.LCURLY -> depth++
                        T.RPAREN, T.RBRACK, T.RCURLY -> depth--
                    }
                    k++
                }
            }
        }

        private fun callArgEntry(range: IntRange): Entry? {
            val path = purePath(range, allowColon = false) ?: return null
            return pathEntry(path)
        }

        /**
         * 兜底（裸表达式行，如 `foo()`、`x`）：收集不在 `.`/`:` 后面的名字，连着的 `.b.c` 一起作为路径。
         * 已归给 local / 赋值语句、函数名、`goto` / 标签的名字不收。
         */
        private fun collectFallback() {
            var k = 0
            while (k < n) {
                val t = toks[k]
                if ((t.type == T.NAME || t.type == T.SELF) && !handled[k]) {
                    val prev = if (k > 0) toks[k - 1] else null
                    val afterMember = prev != null && (prev.type == T.DOT || prev.type == T.COLON)
                    val isLabel = prev != null && (prev.type == T.DOUBLE_COLON || (prev.type == T.KEYWORD && prev.text == "goto"))
                    if (!afterMember && !isLabel) {
                        var e = k + 1
                        while (type(e) == T.DOT && type(e + 1) == T.NAME) e += 2
                        purePath(k until e, allowColon = false)?.let { path -> pathEntry(path)?.let { out += it } }
                        k = e
                        continue
                    }
                }
                k++
            }
        }

        /** 链首若是 self，显示末段字段（`self.isAble` → `isAble`）；单独的 self 不显示。 */
        private fun pathEntry(path: List<String>): Entry? {
            if (path.size == 1 && path[0] == "self") return null
            return Entry(path.last(), path)
        }

        // ---------- 小工具 ----------

        private fun type(i: Int): IElementType? = if (i in 0 until n) toks[i].type else null
        private fun isKeyword(i: Int, word: String) = type(i) == T.KEYWORD && toks[i].text == word
        private fun isOp(i: Int, op: String) = type(i) == T.OPERATOR && toks[i].text == op
        private fun markHandled(from: Int, to: Int) {
            for (j in maxOf(from, 0) until minOf(to, n)) handled[j] = true
        }
    }

    private fun isDisplayableName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        if (name == "self") return false
        if (name.any { it == '.' || it == ':' || it == '[' }) return false
        return true
    }

    /** 按 (displayName + 路径或字面量) 去重，保持首次出现顺序。 */
    private fun dedup(entries: List<Entry>): List<Entry> {
        if (entries.size <= 1) return entries
        val seen = HashSet<String>()
        return entries.filter { seen.add(it.displayName + "|" + (it.pathSegments?.joinToString(".") ?: "#lit:${it.literalText}")) }
    }

    /** 表达式里允许出现、不表示语句结束的关键字。 */
    private val EXPR_KEYWORDS = setOf("and", "or", "not", "function")
}

package com.fixcode.intellij.lua.structure

import com.fixcode.intellij.lua.editor.LuaBlocks
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T

/**
 * 文件大纲：只看 token，认出顶层的声明，给「结构」窗口用。
 *
 * 不走 emmylua_ls 的 documentSymbol：结构窗口只在打开时取一次，服务器还在建索引、IDE 在索引或请求被取消时
 * 拿到的是空列表，之后不会自己刷新（UE 项目里很常见）。词法扫一遍是毫秒级，每次编辑后平台重建，结果总是对的。
 *
 * 认的写法（都只看顶层，函数体里面的不列）：
 * - `local function f()`、`function f()`
 * - `function M:Foo()`、`function M.Foo()`、`function A.B:C()`：挂到 `M`（`A.B`）下面，没有这个节点时补一个分组
 * - `local M = ...`、`G = ...`、`M.Field = ...`；值是表构造 `{ ... }` 时把里面的 `k = v` 列成子项
 */
object LuaOutline {
    enum class Kind { CLASS, VARIABLE, FIELD, FUNCTION, METHOD, STATIC_METHOD }

    class Node(
        val name: String,
        val kind: Kind,
        /** 名字所在的偏移，导航到这里。 */
        val offset: Int,
        /** 整个声明的范围，用来跟随光标选中。 */
        val start: Int,
        var end: Int,
        val params: String? = null,
        val isLocal: Boolean = false,
    ) {
        val children = ArrayList<Node>()
        override fun toString() = name
    }

    fun build(text: CharSequence): List<Node> = Builder(LuaBlocks.lex(text)).run()

    private class Builder(val toks: List<LuaBlocks.Tok>) {
        val roots = ArrayList<Node>()
        /** 顶层按名字找节点（`M`、`A.B`），方法、字段挂上去。 */
        val byPath = HashMap<String, Node>()

        fun run(): List<Node> {
            var i = 0
            while (i < toks.size) {
                i = statement(i)
            }
            return roots
        }

        /** 从 [i] 开始读一条顶层语句，返回下一条语句的开头。 */
        fun statement(i: Int): Int {
            val t = toks[i]
            return when {
                t.isKeyword("local") && kw(i + 1, "function") -> localFunction(i)
                t.isKeyword("local") -> localVars(i)
                t.isKeyword("function") -> function(i)
                // `t[k].f = 1` 里的 f 不是语句开头
                t.type == T.NAME && toks.getOrNull(i - 1)?.let { it.type == T.DOT || it.type == T.COLON } != true -> assignment(i)
                else -> skip(i)
            }
        }

        fun localFunction(i: Int): Int {
            val nameTok = toks.getOrNull(i + 2) ?: return i + 2
            if (nameTok.type != T.NAME) return skip(i + 1)
            val end = blockEnd(i + 1)
            add(null, Node(nameTok.text, Kind.FUNCTION, nameTok.start, toks[i].start, endOffset(end), params(i + 3), isLocal = true))
            return end + 1
        }

        fun function(i: Int): Int {
            // function A.B.C:D(...)
            val path = ArrayList<LuaBlocks.Tok>()
            var j = i + 1
            var colon = false
            while (j < toks.size && toks[j].type == T.NAME) {
                path += toks[j]
                val sep = toks.getOrNull(j + 1)
                if (sep != null && (sep.type == T.DOT || sep.type == T.COLON) && toks.getOrNull(j + 2)?.type == T.NAME) {
                    if (sep.type == T.COLON) colon = true
                    j += 2
                } else {
                    j++
                    break
                }
            }
            val end = blockEnd(i)
            if (path.isEmpty()) return end + 1 // 匿名函数当语句，不列
            val last = path.last()
            val node = if (path.size == 1) {
                Node(last.text, Kind.FUNCTION, last.start, toks[i].start, endOffset(end), params(j))
            } else {
                Node(last.text, if (colon) Kind.METHOD else Kind.STATIC_METHOD, last.start, toks[i].start, endOffset(end), params(j))
            }
            add(path.dropLast(1).joinToString(".") { it.text }.ifEmpty { null }, node)
            return end + 1
        }

        fun localVars(i: Int): Int {
            // local a, b <const> = v1, v2
            val names = ArrayList<LuaBlocks.Tok>()
            var j = i + 1
            while (j < toks.size && toks[j].type == T.NAME) {
                names += toks[j]
                j++
                // Lua 5.4 属性 <const> / <close>
                if (op(j, "<") && toks.getOrNull(j + 1)?.type == T.NAME && op(j + 2, ">")) j += 3
                if (toks.getOrNull(j)?.type == T.COMMA) j++ else break
            }
            if (names.isEmpty()) return skip(i)
            val nodes = names.map { Node(it.text, Kind.VARIABLE, it.start, toks[i].start, it.end, isLocal = true) }
            nodes.forEach { add(null, it) }
            if (toks.getOrNull(j)?.type != T.ASSIGN) return j
            return value(j + 1, nodes.first())
        }

        /** `G = v`、`M.Field = v`、`A.B.C = v`；不是赋值就当表达式语句跳过。 */
        fun assignment(i: Int): Int {
            val path = arrayListOf(toks[i])
            var j = i + 1
            while (toks.getOrNull(j)?.type == T.DOT && toks.getOrNull(j + 1)?.type == T.NAME) {
                path += toks[j + 1]
                j += 2
            }
            if (toks.getOrNull(j)?.type != T.ASSIGN) return skip(i)
            val last = path.last()
            val owner = path.dropLast(1).joinToString(".") { it.text }.ifEmpty { null }
            val isFunction = kw(j + 1, "function")
            val kind = when {
                isFunction -> if (owner == null) Kind.FUNCTION else Kind.STATIC_METHOD
                owner == null -> Kind.VARIABLE
                else -> Kind.FIELD
            }
            val node = Node(last.text, kind, last.start, toks[i].start, last.end, if (isFunction) params(j + 2) else null)
            // 已经有同名节点（先 local M = {}，后 M = ...）就不重复列
            val path0 = (listOfNotNull(owner) + last.text).joinToString(".")
            if (byPath.containsKey(path0)) return value(j + 1, null)
            add(owner, node)
            return value(j + 1, node)
        }

        /** 读等号右边的值：表构造列字段，函数取范围；返回下一条语句的开头。 */
        fun value(i: Int, node: Node?): Int {
            val t = toks.getOrNull(i) ?: return i
            if (t.isKeyword("function")) {
                val end = blockEnd(i)
                node?.end = endOffset(end)
                return end + 1
            }
            if (t.type == T.LCURLY) {
                val close = matching(i)
                if (node != null) {
                    tableFields(i + 1, close, node)
                    node.end = endOffset(close)
                }
                return close + 1
            }
            return skip(i)
        }

        /** 表构造 `{ a = 1, f = function() end, ["k"] = 2 }` 里的具名字段。 */
        fun tableFields(from: Int, to: Int, owner: Node) {
            var j = from
            while (j < to) {
                val t = toks[j]
                if (t.type == T.NAME && toks.getOrNull(j + 1)?.type == T.ASSIGN && fieldStart(j)) {
                    val isFunction = kw(j + 2, "function")
                    val field = Node(t.text, if (isFunction) Kind.STATIC_METHOD else Kind.FIELD, t.start, t.start, t.end, if (isFunction) params(j + 3) else null)
                    owner.children += field
                    j += 2
                    // 值是嵌套表时也列出来
                    if (toks.getOrNull(j)?.type == T.LCURLY) {
                        val close = matching(j)
                        tableFields(j + 1, close, field)
                        field.end = endOffset(close)
                        j = close + 1
                        continue
                    }
                    if (isFunction) {
                        val end = blockEnd(j)
                        field.end = endOffset(end)
                        j = end + 1
                        continue
                    }
                }
                j = when (t.type) {
                    T.LCURLY, T.LPAREN, T.LBRACK -> matching(j) + 1
                    else -> if (t.isKeyword("function")) blockEnd(j) + 1 else j + 1
                }
            }
        }

        /** 表构造里，名字前面是 `{` `,` `;` 才是字段名（排除 `a = b == c` 这类）。 */
        fun fieldStart(j: Int): Boolean {
            val prev = toks.getOrNull(j - 1) ?: return true
            return prev.type == T.LCURLY || prev.type == T.COMMA || prev.type == T.SEMI
        }

        fun add(ownerPath: String?, node: Node) {
            if (ownerPath == null) {
                roots += node
                byPath.putIfAbsent(node.name, node)
                return
            }
            val owner = byPath[ownerPath] ?: Node(ownerPath, Kind.CLASS, node.offset, node.start, node.end).also {
                roots += it
                byPath[ownerPath] = it
            }
            owner.children += node
            byPath.putIfAbsent("$ownerPath.${node.name}", node)
        }

        /** 参数表 `(a, b, ...)` 的文字；[i] 应当指向 `(`。 */
        fun params(i: Int): String? {
            if (toks.getOrNull(i)?.type != T.LPAREN) return null
            val close = matching(i)
            return toks.subList(i + 1, close.coerceAtMost(toks.size)).joinToString("", "(", ")") {
                if (it.type == T.COMMA) ", " else it.text
            }
        }

        /** 从块开头（`function` 等）找到配对的 `end` / `until` 的下标；不配对时到文件尾。 */
        fun blockEnd(i: Int): Int {
            var depth = 0
            var loopHeaders = 0
            var j = i
            while (j < toks.size) {
                val t = toks[j]
                if (t.isKeyword) when (t.text) {
                    "function", "if", "repeat" -> depth++
                    "while", "for" -> { depth++; loopHeaders++ }
                    "do" -> if (loopHeaders > 0) loopHeaders-- else depth++
                    "end", "until" -> {
                        depth--
                        if (depth == 0) return j
                    }
                }
                j++
            }
            return toks.size - 1
        }

        /** 括号配对：[i] 指向 `{` `(` `[`，返回配对的下标；不配对时到文件尾。 */
        fun matching(i: Int): Int {
            var depth = 0
            var j = i
            while (j < toks.size) {
                when (toks[j].type) {
                    T.LCURLY, T.LPAREN, T.LBRACK -> depth++
                    T.RCURLY, T.RPAREN, T.RBRACK -> {
                        depth--
                        if (depth == 0) return j
                    }
                }
                j++
            }
            return toks.size - 1
        }

        /** 跳过一条不认识的语句：遇到块就整块跳过，括号整对跳过，到下一个可能的语句开头为止。 */
        fun skip(i: Int): Int {
            val t = toks.getOrNull(i) ?: return i + 1
            return when {
                t.isKeyword && t.text in setOf("function", "if", "while", "for", "repeat", "do") -> blockEnd(i) + 1
                t.type == T.LCURLY || t.type == T.LPAREN || t.type == T.LBRACK -> matching(i) + 1
                else -> i + 1
            }
        }

        fun kw(i: Int, word: String) = toks.getOrNull(i)?.isKeyword(word) == true
        fun op(i: Int, text: String) = toks.getOrNull(i)?.let { it.type == T.OPERATOR && it.text == text } == true
        fun endOffset(i: Int) = toks.getOrNull(i)?.end ?: toks.lastOrNull()?.end ?: 0
    }
}

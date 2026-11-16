package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * 后缀模板（`x.if` + Tab）。表达式边界只靠词法器往前找：名字、成员链 `a.b:c`、下标 `a[1]`、调用 `f(x)`、
 * 括号表达式 `(a + b)`，以及单独的数字 / 字符串。括号不配对、前面接着 `.` 却不是名字这类拿不准的情况不提供。
 *
 * emmylua_ls 自己也有后缀补全（默认用 `@` 触发，`.emmyrc.json` 的 `completion.postfix`），两边不冲突。
 */
class LuaPostfixTemplateProvider : PostfixTemplateProvider {
    private val templates: Set<PostfixTemplate> = linkedSetOf(
        LuaPostfixTemplate("if", "if expr then end", "if {expr} then\n\t{END}\nend", this),
        LuaPostfixTemplate("ifnot", "if not expr then end", "if not {expr} then\n\t{END}\nend", this),
        LuaPostfixTemplate("nn", "if expr ~= nil then end", "if {expr} ~= nil then\n\t{END}\nend", this),
        LuaPostfixTemplate("null", "if expr == nil then end", "if {expr} == nil then\n\t{END}\nend", this),
        LuaPostfixTemplate("local", "local name = expr", "local {name} = {expr}{END}", this),
        LuaPostfixTemplate("return", "return expr", "return {expr}{END}", this),
        LuaPostfixTemplate("print", "print(expr)", "print({expr}){END}", this),
        LuaPostfixTemplate("fori", "for i = 1, expr do end", "for {i} = 1, {expr} do\n\t{END}\nend", this),
        LuaPostfixTemplate("forp", "for k, v in pairs(expr) do end", "for {k}, {v} in pairs({expr}) do\n\t{END}\nend", this),
        LuaPostfixTemplate("fora", "for _, v in ipairs(expr) do end", "for _, {v} in ipairs({expr}) do\n\t{END}\nend", this),
        LuaPostfixTemplate("par", "(expr)", "({expr}){END}", this),
    )

    override fun getId(): String = "lua"
    override fun getPresentableName(): String = "Lua"
    override fun getTemplates(): Set<PostfixTemplate> = templates
    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'
    override fun preExpand(file: PsiFile, editor: Editor) {}
    override fun afterExpand(file: PsiFile, editor: Editor) {}
    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
}

/**
 * `body` 里 `{expr}` 换成表达式原文，`{END}` 是结束位置，其他 `{名字}` 是可改的变量（默认值就是名字，
 * `.local` 的 `{name}` 按表达式猜）；`\n` 后接当前行缩进，`\t` 换成一级缩进。
 */
class LuaPostfixTemplate(
    key: String,
    example: String,
    private val body: String,
    provider: PostfixTemplateProvider,
) : PostfixTemplate("lua.postfix.$key", key, example, provider) {

    override fun calcDescription(): String = "把表达式改写成 <code>${presentableName.escapeHtml()}</code>：$example"

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean =
        LuaPostfixExpr.findStart(copyDocument.charsSequence, newOffset) != null

    override fun expand(context: PsiElement, editor: Editor) {
        val project = editor.project ?: return
        val document = editor.document
        val end = editor.caretModel.offset
        val start = LuaPostfixExpr.findStart(document.charsSequence, end) ?: return
        val expr = document.charsSequence.subSequence(start, end).toString()
        if ('$' in expr) return // 模板语法里 `$` 有特殊含义；Lua 代码里几乎只会出现在字符串里，不值得转义
        val indent = LuaBlocks.indentAt(document.charsSequence, LuaBlocks.lineStart(document.charsSequence, start))
        val unit = LuaIndent.unit(project, document)
        document.deleteString(start, end)
        editor.caretModel.moveToOffset(start)

        val manager = TemplateManager.getInstance(project)
        val template = manager.createTemplate("", "")
        template.isToReformat = false
        template.setToIndent(false)
        val seen = HashSet<String>()
        var last = 0
        val text = body.replace("\n", "\n$indent").replace("\t", unit)
        for (m in PLACEHOLDER.findAll(text)) {
            if (m.range.first > last) template.addTextSegment(text.substring(last, m.range.first))
            when (val name = m.groupValues[1]) {
                "expr" -> template.addTextSegment(expr)
                "END" -> template.addEndVariable()
                else -> {
                    if (seen.add(name)) {
                        val default = if (name == "name") LuaPostfixExpr.suggestName(expr) else name
                        template.addVariable(name, "\"$default\"", "\"$default\"", true)
                    }
                    template.addVariableSegment(name)
                }
            }
            last = m.range.last + 1
        }
        if (last < text.length) template.addTextSegment(text.substring(last))
        manager.startTemplate(editor, template)
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{(\w+)}""")
        fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/** 后缀模板的表达式边界：只认词法上能确定的简单表达式。 */
object LuaPostfixExpr {
    private val CHAIN_END = setOf(T.NAME, T.SELF, T.RPAREN, T.RBRACK)

    /** `offset` 正前面那个表达式的起点；认不准返回 null。 */
    fun findStart(text: CharSequence, offset: Int): Int? {
        if (offset <= 0 || offset > text.length) return null
        val lineEnd = LuaBlocks.lineEnd(text, offset)
        // 从文件头扫，才能知道这一行是不是在长字符串 / 长注释里
        val all = LuaBlocks.lexAll(text, 0, lineEnd)
        var i = all.indexOfLast { it.end == offset }
        if (i < 0 || all[i].start >= offset) return null
        val toks = all
        val lastTok = toks[i]
        if (lastTok.type in T.COMMENTS || lastTok.type == T.WHITE_SPACE) return null
        if (lastTok.type == T.NUMBER || lastTok.type == T.STRING || lastTok.type == T.CONSTANT) {
            val prev = toks.getOrNull(i - 1)
            if (prev != null && (prev.type == T.DOT || prev.type == T.COLON)) return null
            // 没闭合的字符串（光标还在字符串里）不算
            if (lastTok.type == T.STRING && (lastTok.text.length < 2 || lastTok.text.last() != lastTok.text.first())) return null
            return lastTok.start
        }
        while (true) {
            val tok = toks[i]
            when (tok.type) {
                T.NAME, T.SELF -> {
                    val prev = toks.getOrNull(i - 1)
                    if (prev == null || (prev.type != T.DOT && prev.type != T.COLON)) return tok.start
                    val owner = toks.getOrNull(i - 2) ?: return null
                    if (owner.type !in CHAIN_END) return null
                    i -= 2
                }
                T.RPAREN, T.RBRACK -> {
                    val open = if (tok.type == T.RPAREN) T.LPAREN else T.LBRACK
                    val j = matchingOpen(toks, i, open, tok.type) ?: return null
                    val prev = toks.getOrNull(j - 1)
                    if (prev != null && prev.type in CHAIN_END) {
                        i = j - 1 // 调用 / 下标：接着往前找被调用的东西
                    } else {
                        return if (tok.type == T.RPAREN) toks[j].start else null // `(a + b)`；单独的 `[1]` 不是表达式
                    }
                }
                else -> return null
            }
        }
    }

    private fun matchingOpen(toks: List<LuaBlocks.Tok>, closeIndex: Int, open: Any, close: Any): Int? {
        var depth = 0
        for (k in closeIndex downTo 0) {
            val t = toks[k].type
            if (t == close) depth++
            else if (t == open) {
                depth--
                if (depth == 0) return k
            }
            // 括号跨了语句（中间出现关键字块）就不认
            if (toks[k].isKeyword && toks[k].text in setOf("end", "then", "do", "function", "local", "return")) return null
        }
        return null
    }

    /** `.local` 的变量名：取最后一个名字，`GetFoo()` 去掉 Get 变成 `foo`。 */
    fun suggestName(expr: String): String {
        val name = LuaBlocks.lex(expr).lastOrNull { it.type == T.NAME }?.text ?: return "value"
        val stripped = if (name.length > 3 && (name.startsWith("Get") || name.startsWith("get")) && name[3].isUpperCase()) name.substring(3) else name
        return stripped.replaceFirstChar { it.lowercaseChar() }
    }
}

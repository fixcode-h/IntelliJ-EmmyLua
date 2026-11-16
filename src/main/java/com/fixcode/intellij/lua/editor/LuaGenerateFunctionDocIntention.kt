package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lang.LuaLanguage
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * 意图动作「生成函数注解」：光标在 `function name(a, b)` 这一行时，在上面补 `---@param a any`……，
 * 函数体里有带值的 `return` 时再补 `---@return any`。已经写了的 `@param` / `@return` 不重复。
 *
 * emmylua_ls 0.25.1 的 codeAction 只有「禁用诊断」「cast 去 nil」「加已知标签」「换成局部别名」几种快速修复，
 * 没有生成注解，所以在 IDE 侧用词法器做。类型留 `any`，交给用户改（emmylua_ls 会补全类型名）。
 */
class LuaGenerateFunctionDocIntention : IntentionAction, DumbAware {
    override fun getText(): String = "生成函数注解"
    override fun getFamilyName(): String = "生成函数注解（---@param / ---@return）"
    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
        file.language.isKindOf(LuaLanguage) && LuaFunctionDoc.plan(editor.document.charsSequence, editor.caretModel.offset) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val plan = LuaFunctionDoc.plan(editor.document.charsSequence, editor.caretModel.offset) ?: return
        editor.document.insertString(plan.offset, plan.text)
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        invoke(project, editor, file)
        return IntentionPreviewInfo.DIFF
    }
}

object LuaFunctionDoc {
    class Plan(val offset: Int, val text: String)

    private val DOC_PARAM = Regex("""^---\s*@param\s+([A-Za-z_][A-Za-z0-9_]*|\.\.\.)""")
    private val DOC_RETURN = Regex("""^---\s*@return\b""")

    fun plan(text: CharSequence, caret: Int): Plan? {
        val lineStart = LuaBlocks.lineStart(text, caret)
        val lineEnd = LuaBlocks.lineEnd(text, caret)
        val tokens = LuaBlocks.lex(text)
        val fnIndex = tokens.indexOfFirst { it.start in lineStart until lineEnd && it.isKeyword("function") }
        if (fnIndex < 0) return null
        val params = params(tokens, fnIndex) ?: return null

        // 函数行正上方连着的 `---` 行就是它的注解
        val existingParams = HashSet<String>()
        var hasReturn = false
        var p = lineStart
        while (p > 0) {
            val prevStart = LuaBlocks.lineStart(text, p - 1)
            val line = text.subSequence(prevStart, p - 1).toString().trim()
            if (!line.startsWith("---")) break
            DOC_PARAM.find(line)?.let { existingParams.add(it.groupValues[1]) }
            if (DOC_RETURN.containsMatchIn(line)) hasReturn = true
            p = prevStart
        }

        val missing = params.filterNot { it in existingParams }
        val needReturn = !hasReturn && returnsValue(tokens, fnIndex)
        if (missing.isEmpty() && !needReturn) return null
        val indent = LuaBlocks.indentAt(text, lineStart)
        val sb = StringBuilder()
        missing.forEach { sb.append(indent).append("---@param ").append(it).append(" any\n") }
        if (needReturn) sb.append(indent).append("---@return any\n")
        return Plan(lineStart, sb.toString())
    }

    /** `function a.b:c(x, y, ...)` 的参数名；不是完整的参数表时返回 null。 */
    private fun params(tokens: List<LuaBlocks.Tok>, fnIndex: Int): List<String>? {
        var i = fnIndex + 1
        if (tokens.getOrNull(i)?.type == T.NAME) {
            i++
            while (tokens.getOrNull(i)?.type == T.DOT || tokens.getOrNull(i)?.type == T.COLON) {
                if (tokens.getOrNull(i + 1)?.type != T.NAME) return null
                i += 2
            }
        }
        if (tokens.getOrNull(i)?.type != T.LPAREN) return null
        i++
        val names = ArrayList<String>()
        while (true) {
            val tok = tokens.getOrNull(i) ?: return null
            when {
                tok.type == T.RPAREN -> return names
                tok.type == T.NAME || tok.type == T.ELLIPSIS -> {
                    names.add(tok.text)
                    val next = tokens.getOrNull(i + 1) ?: return null
                    if (next.type == T.COMMA) i += 2
                    else if (next.type == T.RPAREN) return names
                    else return null
                }
                else -> return null
            }
        }
    }

    /** 函数体（不含里面嵌套的函数）有没有带值的 `return`。 */
    private fun returnsValue(tokens: List<LuaBlocks.Tok>, fnIndex: Int): Boolean {
        var found = false
        var closed = false
        val body = tokens.subList(fnIndex, tokens.size)
        LuaBlocks.scan("", body) { tok, stack ->
            if (closed || found) return@scan
            if (stack.isEmpty()) { closed = true; return@scan }
            if (tok.isKeyword("return") && stack.count { it.tok.isKeyword("function") } == 1) {
                val next = body.getOrNull(body.indexOf(tok) + 1)
                found = next != null && !(next.isKeyword && next.text in setOf("end", "else", "elseif", "until")) && next.type != T.SEMI
            }
        }
        return found
    }
}

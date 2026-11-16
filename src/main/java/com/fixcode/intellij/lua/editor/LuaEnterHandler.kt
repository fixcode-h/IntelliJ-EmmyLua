package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lang.LuaLanguage
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * 回车：
 * - `then` / `do` / `repeat` / `else` / `function f(...)` / `{` / `(` 之后：新行缩进一级；后面没有配对的收尾时补上
 *   `end` / `until` / `}` / `)`，光标留在中间一行。光标后面原本就是收尾（`if x then|end`）时把收尾拆到下一行。
 * - `---` 注解行行尾回车：新行续 `--- `。普通 `--` 注释不续。
 *
 * 都只看词法 token（[LuaBlocks]），配不配对拿不准时不补。
 */
class LuaEnterHandler : EnterHandlerDelegateAdapter() {
    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (!file.language.isKindOf(LuaLanguage)) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        PsiDocumentManager.getInstance(file.project).doPostponedOperationsAndUnblockDocument(document)
        val caret = editor.caretModel.offset
        val text = document.charsSequence
        val curLineStart = LuaBlocks.lineStart(text, caret)
        if (curLineStart == 0) return EnterHandlerDelegate.Result.Continue
        val prevLineStart = LuaBlocks.lineStart(text, curLineStart - 1)
        val curLineEnd = LuaBlocks.lineEnd(text, caret)
        val rest = text.subSequence(caret, curLineEnd).toString().trim()
        val unit = LuaIndent.unit(file.project, document)

        if (continueDocComment(editor, text, prevLineStart, curLineStart, curLineEnd, rest)) {
            return EnterHandlerDelegate.Result.Continue
        }
        closeBlock(editor, text, prevLineStart, curLineStart, curLineEnd, rest, unit)
        return EnterHandlerDelegate.Result.Continue
    }

    private fun continueDocComment(editor: Editor, text: CharSequence, prevLineStart: Int, curLineStart: Int, curLineEnd: Int, rest: String): Boolean {
        if (rest.isNotEmpty()) return false
        val prevLine = text.subSequence(prevLineStart, LuaBlocks.lineEnd(text, prevLineStart)).toString()
        if (!DOC_LINE.matches(prevLine)) return false
        // 确认 `---` 真是注释，不是长字符串里的内容
        val indent = LuaBlocks.indentAt(text, prevLineStart)
        val dashAt = prevLineStart + indent.length
        val tok = LuaBlocks.lexAll(text, 0, minOf(text.length, dashAt + 3)).lastOrNull { it.start <= dashAt }
        if (tok == null || tok.start != dashAt || tok.type !in T.COMMENTS) return false
        val insert = "$indent--- "
        editor.document.replaceString(curLineStart, curLineEnd, insert)
        editor.caretModel.moveToOffset(curLineStart + insert.length)
        return true
    }

    private fun closeBlock(editor: Editor, text: CharSequence, prevLineStart: Int, curLineStart: Int, curLineEnd: Int, rest: String, unit: String) {
        val tokens = LuaBlocks.lex(text)
        val prevTokens = tokens.filter { it.start >= prevLineStart && it.start < curLineStart }
        val last = prevTokens.lastOrNull() ?: return
        val stack = LuaBlocks.scan(text, tokens.filter { it.start < curLineStart })
        val top = stack.lastOrNull() ?: return
        if (top.lineStart != prevLineStart || !endsBlockHeader(last, top)) return

        val document = editor.document
        val indent = LuaBlocks.indentAt(text, prevLineStart)
        val inner = indent + unit
        val restToks = LuaBlocks.lex(rest)
        val restStartsWithCloser = restToks.firstOrNull()?.text == top.closer
        val tail: String? = when {
            // `if x then|end`、`{|}`：收尾已经在了，拆到自己的一行
            restStartsWithCloser -> rest
            rest.isEmpty() || ONLY_BRACKETS.matches(rest) ->
                if (LuaBlocks.hasMatchingCloser(text, tokens, top)) null else top.closer + rest
            else -> return // 光标后面还有正文：只是普通的断行
        }
        if (tail == null) {
            if (rest.isEmpty()) {
                document.replaceString(curLineStart, curLineEnd, inner)
                editor.caretModel.moveToOffset(curLineStart + inner.length)
            }
            return
        }
        document.replaceString(curLineStart, curLineEnd, "$inner\n$indent$tail")
        editor.caretModel.moveToOffset(curLineStart + inner.length)
    }

    /** 上一行最后一个 token 是不是「块头的结尾」：`then` `do` `repeat` `else`、函数参数表的 `)`、`{`、`(`。 */
    private fun endsBlockHeader(last: LuaBlocks.Tok, top: LuaBlocks.Opener): Boolean = when {
        last.isKeyword("then") -> top.tok.isKeyword("if") || top.tok.isKeyword("elseif")
        last.isKeyword("do") -> top.tok.text in setOf("while", "for", "do")
        last.isKeyword("repeat") || last.isKeyword("else") -> top.tok === last
        last.type == T.RPAREN -> top.tok.isKeyword("function")
        last.type == T.LCURLY || last.type == T.LPAREN -> top.tok === last
        else -> false
    }

    private companion object {
        val DOC_LINE = Regex("""^\s*---(?!-).*$""")
        val ONLY_BRACKETS = Regex("""^[)}\],;\s]+$""")
    }
}

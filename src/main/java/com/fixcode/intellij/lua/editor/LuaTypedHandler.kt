package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lang.LuaLanguage
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * 在一行开头打完 `end` / `else` / `elseif` / `until` / `}` / `)` 时，把这一行退回开块那一行的缩进。
 *
 * 只在这一行到光标为止只有这一个词时动：打成 `ending` 这类别的词时（上一个字符时已经回退过）再按普通行算回去。
 */
class LuaTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (!file.language.isKindOf(LuaLanguage)) return Result.CONTINUE
        if (!(c.isLetterOrDigit() || c == '_' || c == '}' || c == ')')) return Result.CONTINUE
        val document = editor.document
        val text = document.charsSequence
        val caret = editor.caretModel.offset
        val lineStart = LuaBlocks.lineStart(text, caret)
        val word = text.subSequence(lineStart, caret).toString().trimStart()
        if (word.isEmpty() || word.any { it.isWhitespace() }) return Result.CONTINUE
        val isCloser = word in CLOSERS
        val wasCloser = word.length > 1 && word.dropLast(1) in CLOSERS && CLOSERS.none { it.startsWith(word) }
        if (!isCloser && !wasCloser) return Result.CONTINUE
        // `end` 后面紧跟着名字字符时（在已有文字前面打字）不是独立的词
        if (isCloser && caret < text.length && (text[caret].isLetterOrDigit() || text[caret] == '_')) return Result.CONTINUE

        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
        val indent = LuaIndent.lineIndent(text, lineStart, LuaIndent.unit(project, document)) ?: return Result.CONTINUE
        LuaIndent.setIndent(document, lineStart, indent)
        return Result.CONTINUE
    }

    private companion object {
        val CLOSERS = setOf("end", "else", "elseif", "until", "}", ")")
    }
}

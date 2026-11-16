package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lexer.LuaTokenTypes
import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Surround With（Ctrl+Alt+T）：按选中的整行包一层。扁平 PSI 没有语句节点，所以只交出首尾两个 token，
 * 包裹时按它们所在的行算范围。
 */
class LuaSurroundDescriptor : SurroundDescriptor {
    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
        val text = file.viewProvider.document?.charsSequence ?: file.text
        if (startOffset >= endOffset || endOffset > text.length) return PsiElement.EMPTY_ARRAY
        val from = LuaBlocks.lineStart(text, startOffset)
        // 选区停在下一行行首时（整行选择）不把那一行算进来
        val to = LuaBlocks.lineEnd(text, if (endOffset > from && text[endOffset - 1] == '\n') endOffset - 1 else endOffset)
        var first: PsiElement? = null
        var last: PsiElement? = null
        var p = from
        while (p < to) {
            val leaf = file.findElementAt(p) ?: break
            if (leaf.node.elementType != LuaTokenTypes.WHITE_SPACE) {
                if (first == null) first = leaf
                last = leaf
            }
            p = maxOf(leaf.textRange.endOffset, p + 1)
        }
        if (first == null || last == null) return PsiElement.EMPTY_ARRAY
        return arrayOf(first, last)
    }

    override fun getSurrounders(): Array<Surrounder> = SURROUNDERS

    override fun isExclusive(): Boolean = false

    private companion object {
        val SURROUNDERS: Array<Surrounder> = arrayOf(
            LuaLineSurrounder("if ... then / end", "if true then", "end", select = "true"),
            LuaLineSurrounder("do / end", "do", "end"),
            LuaLineSurrounder("for i = 1, n do / end", "for i = 1, n do", "end", select = "n"),
            LuaLineSurrounder("function() / end", "function()", "end", caretAtHeaderStart = true),
            LuaLineSurrounder("--region / --endregion", "--region description", "--endregion", select = "description"),
        )
    }
}

/**
 * 把首尾 token 所在的整行包进 `header` / `footer`：块头块尾用这些行里最浅的缩进，正文多缩一级。
 * 包完选中 `select`（如 `if true then` 的 `true`），方便直接改写。
 */
class LuaLineSurrounder(
    private val description: String,
    private val header: String,
    private val footer: String,
    private val select: String? = null,
    private val caretAtHeaderStart: Boolean = false,
) : Surrounder {
    override fun getTemplateDescription(): String = description

    override fun isApplicable(elements: Array<out PsiElement>): Boolean = elements.isNotEmpty()

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val document = editor.document
        val text = document.charsSequence
        val start = LuaBlocks.lineStart(text, elements.first().textRange.startOffset)
        val end = LuaBlocks.lineEnd(text, elements.last().textRange.endOffset)
        val lines = text.subSequence(start, end).toString().split('\n')
        val base = lines.filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it == ' ' || it == '\t' } }
            .minByOrNull { it.length } ?: ""
        val unit = LuaIndent.unit(project, document)
        val body = lines.joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith(base) -> base + unit + line.substring(base.length)
                else -> unit + line // 制表符和空格混用时不去对齐，只多缩一级
            }
        }
        val replacement = "$base$header\n$body\n$base$footer"
        document.replaceString(start, end, replacement)
        val headerStart = start + base.length
        return when {
            select != null -> {
                val at = headerStart + header.indexOf(select)
                TextRange(at, at + select.length)
            }
            caretAtHeaderStart -> TextRange.from(headerStart, 0)
            else -> TextRange.from(headerStart + header.length, 0)
        }
    }
}

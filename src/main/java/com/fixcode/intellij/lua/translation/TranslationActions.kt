package com.fixcode.intellij.lua.translation

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.util.concurrent.CompletableFuture
import javax.swing.JEditorPane
import javax.swing.ScrollPaneConstants

/**
 * 「翻译选中内容」（编辑器右键菜单，Ctrl+Alt+Shift+Y）：译文显示在选区旁的提示框里，和悬停文档一样按「双语对照 /
 * 只显示译文」显示，译文在后台取、到了再补上。没有选中时翻译光标所在的整段注释（不是注释就翻译光标所在行）。
 * 任何文件都能用，不只是 Lua。
 */
class TranslateSelectionAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val started = System.currentTimeMillis()
        val selection = editor.selectionModel
        val range = if (selection.hasSelection()) TextRange(selection.selectionStart, selection.selectionEnd)
        else CommentRange.around(editor, editor.caretModel.offset)
        val doc = editor.document
        if (range == null || range.isEmpty || doc.getText(range).isBlank()) {
            HintManager.getInstance().showInformationHint(editor, "先选中要翻译的文字。")
            return
        }
        val raw = doc.getText(TextRange(range.startOffset, minOf(range.endOffset, range.startOffset + TranslationText.MAX_TOTAL_CHARS)))
        val text = TranslationText.cleanComment(raw) ?: raw.trim()
        TranslationPopup.show(editor, text)
        LuaTranslation.LOG.info(
            "translate.selection chars=${text.length} fromSelection=${selection.hasSelection()} " +
                "provider=${LuaTranslation.service.currentOptions.provider.cacheName} ms=${System.currentTimeMillis() - started}"
        )
    }
}

/** 「清空翻译缓存」：换了翻译服务或觉得译文不对时用。设置页里也有同样的按钮。 */
class ClearTranslationCacheAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val n = clearCache()
        val project = e.project
        val message = "已清空翻译缓存（$n 条）。"
        val bar = project?.let { WindowManager.getInstance().getStatusBar(it) }
        if (bar != null) bar.info = message
        else e.getData(CommonDataKeys.EDITOR)?.let { HintManager.getInstance().showInformationHint(it, message) }
    }

    companion object {
        /** 清空缓存并忘掉失败，返回清掉的条数。 */
        fun clearCache(): Int {
            val service = LuaTranslation.service
            val n = service.cache.count
            service.cache.clear()
            service.resetCooldown()
            return n
        }
    }
}

/** 光标所在的整段注释：连着的注释行（中间只隔一个换行）合成一段，最多上下各 80 行。 */
internal object CommentRange {
    private const val MAX_LINES = 80

    fun around(editor: Editor, offset: Int): TextRange? {
        val doc = editor.document
        if (doc.textLength == 0) return null
        val comments = commentTokens(editor)
        val highlighter = (editor as? EditorEx)?.highlighter
        if (highlighter != null && comments != null) {
            val it = highlighter.createIterator(offset.coerceIn(0, doc.textLength - 1))
            if (!it.atEnd() && isComment(it.tokenType, comments)) {
                var start = it.start
                var end = it.end
                val firstLine = doc.getLineNumber(start)
                // 往前
                val back = highlighter.createIterator(start)
                back.retreat()
                while (!back.atEnd()) {
                    val t = back.tokenType
                    if (isComment(t, comments)) {
                        if (firstLine - doc.getLineNumber(back.start) > MAX_LINES) break
                        start = back.start
                    } else if (!(t == TokenType.WHITE_SPACE && singleBreak(doc, back.start, back.end))) break
                    back.retreat()
                }
                // 往后
                val fwd = highlighter.createIterator(end)
                while (!fwd.atEnd()) {
                    val t = fwd.tokenType
                    if (isComment(t, comments)) {
                        if (doc.getLineNumber(fwd.start) - firstLine > MAX_LINES) break
                        end = fwd.end
                    } else if (!(t == TokenType.WHITE_SPACE && singleBreak(doc, fwd.start, fwd.end))) break
                    fwd.advance()
                }
                return TextRange(start, end)
            }
            if (comments.types.isNotEmpty()) return lineRange(doc, offset)
        }
        return byLines(doc, offset)
    }

    /** 文件语言的注释 token（Lua 是 LINE_COMMENT / BLOCK_COMMENT / DOC_COMMENT / DOC_TAG……）。 */
    private fun commentTokens(editor: Editor): TokenSet? {
        val project = editor.project ?: return null
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        return LanguageParserDefinitions.INSTANCE.forLanguage(file.language)?.commentTokens
    }

    private fun isComment(t: IElementType, comments: TokenSet): Boolean = comments.contains(t)

    private fun singleBreak(doc: Document, start: Int, end: Int): Boolean =
        doc.charsSequence.subSequence(start, end).count { it == '\n' } <= 1

    private fun lineRange(doc: Document, offset: Int): TextRange {
        val line = doc.getLineNumber(offset)
        return TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))
    }

    /** 没有词法信息时按行认（照 UEToVS）：以 --、//、/星号、星号、# 开头的行。 */
    private fun byLines(doc: Document, offset: Int): TextRange {
        val line = doc.getLineNumber(offset)
        if (!isCommentLine(lineText(doc, line))) return lineRange(doc, offset)
        var first = line
        var last = line
        while (first > 0 && line - first < MAX_LINES && isCommentLine(lineText(doc, first - 1))) first--
        while (last < doc.lineCount - 1 && last - line < MAX_LINES && isCommentLine(lineText(doc, last + 1))) last++
        return TextRange(doc.getLineStartOffset(first), doc.getLineEndOffset(last))
    }

    private fun lineText(doc: Document, line: Int): String =
        doc.charsSequence.subSequence(doc.getLineStartOffset(line), doc.getLineEndOffset(line)).toString()

    fun isCommentLine(text: String?): Boolean {
        val t = text.orEmpty().trimStart()
        return t.startsWith("--") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("#")
    }
}

/** 选区旁的翻译提示框：先显示原文，译文到了在界面线程上补进去；光标移动、改文字、点别处时关掉。 */
internal object TranslationPopup {
    private val KEY = Key.create<JBPopup>("emmylua.translation.popup")
    private const val WIDTH = 560
    private const val MAX_HEIGHT = 420

    fun show(editor: Editor, text: String) {
        editor.getUserData(KEY)?.cancel()
        val service = LuaTranslation.service
        val o = service.currentOptions
        val units = TranslationText.split(text, o.target)
        if (units.none { it.translate }) {
            HintManager.getInstance().showInformationHint(editor, "选中的内容不需要翻译（没有英文句子，或已经是目标语言）。")
            return
        }
        val header = LuaTranslation.header()
        val futures = units.map { u ->
            if (!u.translate) null
            else service.cached(u.text)?.let { CompletableFuture.completedFuture(TranslationResult.success(it, true)) } ?: service.translate(u.text)
        }

        val pane = JEditorPane().apply {
            editorKit = HTMLEditorKitBuilder.simple()
            isEditable = false
            background = HintUtil.getInformationColor()
            border = JBUI.Borders.empty(6, 8)
        }
        val scroll = JBScrollPane(pane).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        fun refresh() {
            pane.text = html(header, units, futures, o.display)
            pane.size = Dimension(JBUI.scale(WIDTH), Short.MAX_VALUE.toInt())
            val h = pane.preferredSize.height
            scroll.preferredSize = Dimension(JBUI.scale(WIDTH) + JBUI.scale(4), minOf(h + JBUI.scale(4), JBUI.scale(MAX_HEIGHT)))
            pane.caretPosition = 0
        }
        refresh()

        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
            .setRequestFocus(false)
            .setFocusable(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        editor.putUserData(KEY, popup)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = popup.cancel()
        }, popup)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = popup.cancel()
        }, popup)
        com.intellij.openapi.util.Disposer.register(popup) { if (editor.getUserData(KEY) === popup) editor.putUserData(KEY, null) }

        for (f in futures) {
            if (f == null || f.isDone) continue
            f.whenComplete { _, _ ->
                ApplicationManager.getApplication().invokeLater({
                    if (popup.isDisposed) return@invokeLater
                    refresh()
                    popup.pack(true, true)
                }, ModalityState.any())
            }
        }
        popup.showInBestPositionFor(editor)
    }

    private fun html(header: String, units: List<TextUnit>, futures: List<CompletableFuture<TranslationResult>?>, display: TranslationDisplay): String {
        val sb = StringBuilder("<html><body>")
        sb.append("<p style=\"color: gray;\"><small>").append(HoverMarkdown.escape(header)).append("</small></p>")
        var pending = 0
        var firstError: String? = null
        for ((i, u) in units.withIndex()) {
            val top = if (i == 0) 4 else if (u.gapBefore) 8 else 2
            sb.append("<div style=\"margin-top: ${top}px;\">")
            val original = if (u.translate) HoverMarkdown.inlineHtml(u.text) else "<code>" + HoverMarkdown.escape(u.text).replace("\n", "<br>") + "</code>"
            val f = futures[i]
            val r = if (f != null && f.isDone) f.getNow(null) else null
            if (f != null && !f.isDone) pending++
            if (r != null && !r.ok && firstError == null) firstError = r.error
            val t = r?.text
            when {
                t == null -> sb.append(original)
                display == TranslationDisplay.TRANSLATION_ONLY -> sb.append(HoverMarkdown.inlineHtml(t))
                else -> sb.append(original).append(HoverMarkdown.translationHtml(t))
            }
            sb.append("</div>")
        }
        sb.append(HoverMarkdown.statusHtml(pending, firstError))
        return sb.append("</body></html>").toString()
    }
}

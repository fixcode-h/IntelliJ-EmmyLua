package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.lang.LuaLanguage
import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler
import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.features.LSPSelectionRangeFeature
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 关掉 LSP4IJ 自带的 selectionRange（双击选词、Ctrl+W 扩选）。
 *
 * LSP4IJ 0.21.0 在 IDE 选词的模态进度「正在选择给定偏移的单词…」里**不限时**地等服务器回包（传了 500ms 但没用上），
 * 判断能不能用时还会 `getServerCapabilitiesSync` 等服务器初始化完；emmylua_ls 在建索引或不回时整个编辑器就卡在那个框上。
 * 让出后 IDE 自带的按词选择照常工作，emmylua_ls 的扩选范围由 [LuaSelectionRangeHandler] 限时去取。
 */
class EmmyLuaSelectionRangeFeature : LSPSelectionRangeFeature() {
    override fun isEnabled(file: PsiFile): Boolean = false
}

/** 限时向 emmylua_ls 要 selectionRange；服务器没起来、不支持或超时就不给，IDE 用别的选择器（按词、按行、括号）。 */
class LuaSelectionRangeHandler : ExtendWordSelectionHandler {
    // 扁平 PSI 下每个元素都会被问一次，只在叶子（词法单元）上取，免得同一个位置请求好几遍
    override fun canSelect(e: PsiElement): Boolean = e.firstChild == null && e.language == LuaLanguage

    override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): List<TextRange>? {
        val file = e.containingFile ?: return null
        val vf = file.virtualFile ?: return null
        val item = runningServer(file.project) ?: return null
        val provider = item.serverCapabilities?.selectionRangeProvider ?: return null
        if (provider.isLeft && provider.left != true) return null
        val server = item.server ?: return null

        val document = editor.document
        val params = SelectionRangeParams(
            TextDocumentIdentifier(item.serverWrapper.toUriString(vf)),
            listOf(LSPIJUtils.toPosition(cursorOffset, document)),
        )
        val ranges = await(server.textDocumentService.selectionRange(params), TIMEOUT_MS) ?: return null

        val length = editorText.length
        val result = LinkedHashSet<TextRange>()
        for (top in ranges) {
            var r: SelectionRange? = top
            while (r != null) {
                val start = LSPIJUtils.toOffset(r.range.start, document)
                val end = LSPIJUtils.toOffset(r.range.end, document)
                if (start in 0..end && end <= length) {
                    val range = TextRange(start, end)
                    result.add(range)
                    // 和 LSP4IJ 一样再补一份扩到整行的，扩选能停在整行上
                    ExtendWordSelectionHandlerBase.expandToWholeLinesWithBlanks(editorText, range).filterNotNullTo(result)
                }
                r = r.parent
            }
        }
        return result.toList().ifEmpty { null }
    }

    companion object {
        /** 选词是用户等着的操作：超过这个时间宁可只按词选。 */
        const val TIMEOUT_MS = 500L

        private val LOG = logger<LuaSelectionRangeHandler>()

        /** 只取已经起来的服务器，不在选词里等它启动。 */
        private fun runningServer(project: Project): LanguageServerItem? {
            val item = runCatching { LanguageServerManager.getInstance(project).getLanguageServer(EmmyLuaServer.ID).getNow(null) }.getOrNull() ?: return null
            return item.takeIf { runCatching { it.serverWrapper.serverStatus }.getOrNull() == ServerStatus.started }
        }

        /** 等 [future] 最多 [timeoutMs]；能被取消（模态框的「取消」），超时或出错返回 null 并撤销请求。 */
        internal fun <T> await(future: Future<T>, timeoutMs: Long): T? {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            try {
                while (true) {
                    ProgressManager.checkCanceled()
                    try {
                        return future.get(POLL_MS, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        if (System.nanoTime() >= deadline) {
                            LOG.debug("selectionRange 超过 ${timeoutMs}ms 没回，按普通方式选词")
                            return null
                        }
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: ExecutionException) {
                return null
            } catch (_: java.util.concurrent.CancellationException) {
                return null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } finally {
                // lsp4j 的请求 future 被取消时会给服务器发 $/cancelRequest
                if (!future.isDone) future.cancel(true)
            }
        }

        private const val POLL_MS = 10L
    }
}

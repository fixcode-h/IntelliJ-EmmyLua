package com.fixcode.intellij.lua.translation

import com.fixcode.intellij.lua.lang.LuaLanguage
import com.fixcode.intellij.lua.lsp.EmmyLuaServer
import com.intellij.model.Pointer
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.platform.backend.documentation.DocumentationContent
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor
import com.redhat.devtools.lsp4ij.client.ExecuteLSPFeatureStatus
import com.redhat.devtools.lsp4ij.client.features.LSPHoverFeature
import com.redhat.devtools.lsp4ij.client.indexing.ProjectIndexingManager
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationTarget
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/*
 * 悬停文档里的注释翻译（照 UEToVS 的 Quick Info 翻译）：先显示原文，译文到了再补上，不阻塞悬停。
 *
 * LSP4IJ 的悬停（LSPDocumentationTargetProvider）一次算完 HTML，没法事后补内容；IDE 又会把所有 provider 的结果
 * 合在一起显示，所以开着翻译时：
 *  - [EmmyLuaHoverFeature.isEnabled] 对 emmylua 返回 false，LSP4IJ 自己的悬停不出结果；
 *  - [TranslatedHoverTargetProvider] 自己向 emmylua_ls 要悬停，包成 [TranslatedDocumentationTarget]（是 LSP4IJ
 *    文档目标的子类，文档里的链接照常由 LSP4IJ 处理）；
 *  - 渲染时 [EmmyLuaHoverFeature.getContent] 把要翻译的段交给 [TranslationService]（后台），已缓存的直接填上；
 *    还没到的，目标返回的结果带一个 updates 流，每到一段重新渲染一次。
 * 关掉翻译时一切照旧走 LSP4IJ。
 */

/** 一次悬停里各段的翻译请求（重新渲染时复用，不再发请求）。 */
internal class HoverSession {
    val futures = ConcurrentHashMap<String, CompletableFuture<TranslationResult>>()

    fun pending(): List<CompletableFuture<TranslationResult>> = futures.values.filter { !it.isDone }

    companion object {
        private val CURRENT = ThreadLocal<HoverSession?>()

        fun current(): HoverSession? = CURRENT.get()

        fun <T> with(session: HoverSession, block: () -> T): T {
            val old = CURRENT.get()
            CURRENT.set(session)
            try {
                return block()
            } finally {
                CURRENT.set(old)
            }
        }
    }
}

/** emmylua 的悬停特性：开着翻译时让出给 [TranslatedHoverTargetProvider]，渲染时插入译文。 */
class EmmyLuaHoverFeature : LSPHoverFeature() {
    override fun isEnabled(file: PsiFile): Boolean = super.isEnabled(file) && !LuaTranslation.hoverEnabled()

    override fun getContent(markupContent: MarkupContent, file: PsiFile): String? {
        val session = HoverSession.current() ?: return super.getContent(markupContent, file)
        return try {
            renderTranslated(markupContent, session) { md -> super.getContent(MarkupContent(markupContent.kind, md), file) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LuaTranslation.LOG.warn("translate.hover.renderFailed", e)
            super.getContent(markupContent, file)
        }
    }

    private fun renderTranslated(markup: MarkupContent, session: HoverSession, convert: (String) -> String?): String? {
        val service = LuaTranslation.service
        val o = service.currentOptions
        val source = markup.value.orEmpty()
        val md = HoverMarkdown.parse(source, o.target)
        if (!md.hasTranslatable) return convert(source)
        var pending = 0
        var firstError: String? = null
        val rendered = md.render(o.display) { unit ->
            val f = session.futures.computeIfAbsent(unit.text) { text ->
                service.cached(text)?.let { CompletableFuture.completedFuture(TranslationResult.success(it, true)) } ?: service.translate(text)
            }
            if (!f.isDone) {
                pending++
                null
            } else {
                val r = f.getNow(null)
                if (r?.ok == true) r.text else {
                    if (firstError == null) firstError = r?.error
                    null
                }
            }
        }
        val html = convert(rendered.markdown) ?: return convert(source)
        return HoverMarkdown.fillSlots(html, rendered.slots) + HoverMarkdown.statusHtml(pending, firstError)
    }
}

/** emmylua_ls 的悬停文档 + 译文；译文没到时结果带 updates 流，到一段补一段。 */
class TranslatedDocumentationTarget(
    contents: List<MarkupContent>,
    presentationText: String,
    file: PsiFile,
    server: LanguageServerItem,
) : LSPDocumentationTarget(contents, presentationText, file, server) {

    override fun computeDocumentation(): DocumentationResult? {
        val session = HoverSession()
        val html = HoverSession.with(session) { html }
        val doc = DocumentationResult.documentation(html)
        if (session.pending().isEmpty()) return doc
        return doc.updates(flow {
            while (true) {
                val pending = session.pending()
                if (pending.isEmpty()) break
                CompletableFuture.anyOf(*pending.toTypedArray()).await()
                val updated = readAction { HoverSession.with(session) { html } }
                emit(DocumentationContent.content(updated))
            }
        })
    }

    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)
}

/** 开着翻译时由这里向 emmylua_ls 要悬停（LSP4IJ 的悬停这时对 Lua 让出，见文件开头）。 */
class TranslatedHoverTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (!LuaTranslation.hoverEnabled() || !file.language.isKindOf(LuaLanguage)) return emptyList()
        val project = file.project
        if (project.isDisposed) return emptyList()
        if (ProjectIndexingManager.canExecuteLSPFeature(file) != ExecuteLSPFeatureStatus.NOW) return emptyList()
        val vfile = LSPIJUtils.getFile(file) ?: return emptyList()
        val document = LSPIJUtils.getDocument(vfile) ?: return emptyList()
        val servers = ProgressIndicatorUtils.awaitWithCheckCanceled(
            LanguageServiceAccessor.getInstance(project).getLanguageServers(
                file,
                { it.serverDefinition.id == EmmyLuaServer.ID },
                { it.serverDefinition.id == EmmyLuaServer.ID && it.hoverFeature.isHoverSupported(file) },
            )
        ) ?: return emptyList()
        val params = HoverParams(TextDocumentIdentifier(LSPIJUtils.toUriAsString(file)), LSPIJUtils.toPosition(offset, document))
        val targets = ArrayList<DocumentationTarget>()
        for (server in servers) {
            val hover = ProgressIndicatorUtils.awaitWithCheckCanceled(server.server.textDocumentService.hover(params)) ?: continue
            val contents = LSPDocumentationHelper.getValidMarkupContents(hover)
            if (contents.isEmpty()) continue
            val presentation = hover.range?.let { r ->
                LSPIJUtils.toTextRange(r, document)?.takeIf { it.endOffset <= document.textLength }?.let { document.getText(it) }
            }.orEmpty()
            targets += TranslatedDocumentationTarget(contents, presentation, file, server)
        }
        return targets
    }
}

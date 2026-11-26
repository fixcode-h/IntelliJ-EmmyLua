package com.fixcode.intellij.lua.editor

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.redhat.devtools.lsp4ij.features.foldingRange.LSPFoldingRangeBuilder
import org.eclipse.lsp4j.FoldingRangeKind

/**
 * Lua 的折叠区照用 LSP4IJ 的（来自 emmylua_ls 的 foldingRange），只去掉 `imports` 一类。
 *
 * emmylua_ls 把文件开头连续的 `local X = require(...)` 合成一个 imports 折叠区，IDE 又默认折叠 imports，
 * 打开文件时 require 那几行就缩成「imports ...」，看不到引了哪些模块（用户 2026-11-26 要求不折叠）。
 * 只改「默认折叠」不够：折叠区还在，手一碰又收起来，所以整块不产生。
 */
class LuaFoldingBuilder : LSPFoldingRangeBuilder() {
    override fun buildLanguageFoldRegions(descriptors: MutableList<FoldingDescriptor>, root: PsiElement, document: Document, quick: Boolean) {
        val built = ArrayList<FoldingDescriptor>()
        super.buildLanguageFoldRegions(built, root, document, quick)
        if (built.isEmpty()) return
        val file = root.containingFile
        if (file == null) {
            descriptors.addAll(built)
            return
        }
        // 上面刚取过，LSP4IJ 按文档版本缓存了结果，这里不会再发一次请求
        val imports = LSPFoldingRangeBuilder.getFoldingRanges(file)
            .filter { it.kind == FoldingRangeKind.Imports }
            .mapNotNull { LSPFoldingRangeBuilder.getTextRange(it, file, document, null, null) }
            .toSet()
        built.filterTo(descriptors) { it.range !in imports }
    }
}

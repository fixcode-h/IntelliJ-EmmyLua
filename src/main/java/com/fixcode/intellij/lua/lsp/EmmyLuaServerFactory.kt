package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.project.LuaLibraryRoots
import com.fixcode.intellij.lua.translation.EmmyLuaHoverFeature
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

class EmmyLuaServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider = EmmyLuaServerConnection(project)
    override fun createLanguageClient(project: Project): LanguageClientImpl = EmmyLuaLanguageClient(project)

    /**
     * 悬停换成带注释翻译的（translation/TranslatedHover.kt）；翻译关着时和 LSP4IJ 的一样。
     * 后台进度换成本地化、带阶段和估计百分比的（[EmmyLuaProgressFeature]）。
     */
    override fun createClientFeatures(): LSPClientFeatures = LSPClientFeatures()
        .setHoverFeature(EmmyLuaHoverFeature())
        .setSelectionRangeFeature(EmmyLuaSelectionRangeFeature())
        .setProgressFeature(EmmyLuaProgressFeature())
}

object EmmyLuaServer {
    /** 和 plugin.xml 里 `com.redhat.devtools.lsp4ij.server` 的 id 一致。 */
    const val ID = "emmylua"

    /** 设置变了（库目录、Lua 版本、服务器路径）：重启让 emmylua_ls 重新读配置、重建索引。 */
    fun restart(project: Project) {
        if (project.isDisposed) return
        val manager = LanguageServerManager.getInstance(project)
        manager.stop(ID)
        manager.start(ID)
        // 库目录可能跟着变了：项目树「外部库」一起刷新（要碰磁盘和 VFS，放后台）
        ApplicationManager.getApplication().executeOnPooledThread { LuaLibraryRoots.getInstance(project).refresh() }
    }
}

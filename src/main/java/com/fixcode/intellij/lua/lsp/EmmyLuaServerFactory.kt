package com.fixcode.intellij.lua.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

class EmmyLuaServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider = EmmyLuaServerConnection(project)
    override fun createLanguageClient(project: Project): LanguageClientImpl = EmmyLuaLanguageClient(project)
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
    }
}

package com.fixcode.intellij.lua.lsp

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl

class EmmyLuaLanguageClient(project: Project) : LanguageClientImpl(project) {
    /** emmylua_ls 初始化时按 section `emmylua` 取配置（`workspace/configuration`）。 */
    override fun createSettings(): Any = JsonObject().apply {
        add(EmmyLuaWorkspaceConfig.SECTION, EmmyLuaWorkspaceConfig.build(project))
    }
}

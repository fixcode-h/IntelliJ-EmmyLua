package com.fixcode.intellij.lua.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import java.nio.charset.StandardCharsets

/** 用 stdio 起 emmylua_ls。每次启动重新找可执行文件，改了设置重启服务器就生效。 */
class EmmyLuaServerConnection(private val project: Project) : OSProcessStreamConnectionProvider() {
    override fun start() {
        val located = EmmyLuaServerLocator.locate()
            ?: throw CannotStartProcessException(
                "找不到 emmylua_ls。请在「设置 → 语言和框架 → EmmyLua」里填写 emmylua_ls 的路径，或把它加进 PATH。"
            )
        val cmd = GeneralCommandLine(located.path.toString(), "--editor", "intellij")
            .withCharset(StandardCharsets.UTF_8)
        project.basePath?.let { cmd.withWorkDirectory(it) }
        commandLine = cmd
        super.start()
    }
}

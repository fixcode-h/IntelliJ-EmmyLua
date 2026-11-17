package com.fixcode.intellij.lua.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

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
        writeConfigFile()?.let { cmd.withEnvironment(CONFIG_ENV, it.toString()) }
        commandLine = cmd
        super.start()
    }

    /**
     * 同一份部分配置再写成文件、用环境变量 `EMMYLUALS_CONFIG` 交给 emmylua_ls：
     * `workspace/configuration` 只等 5 秒，IDE 刚启动忙的时候可能超时，库目录就丢了（UE 声明全都找不到）。
     * 这个文件在工作区 `.emmyrc.json` 之前合并，标量以用户文件为准，数组取并集，和配置请求的结果重复也没关系。
     */
    private fun writeConfigFile(): Path? = runCatching {
        val dir = Path.of(PathManager.getSystemPath(), "emmylua", "emmyrc")
        Files.createDirectories(dir)
        val name = (project.locationHash.ifEmpty { project.name }).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = dir.resolve("$name.json")
        Files.writeString(file, EmmyLuaWorkspaceConfig.build(project).toString())
        file
    }.onFailure { LOG.warn("写 emmylua_ls 配置文件失败", it) }.getOrNull()

    companion object {
        const val CONFIG_ENV = "EMMYLUALS_CONFIG"
        private val LOG = logger<EmmyLuaServerConnection>()
    }
}

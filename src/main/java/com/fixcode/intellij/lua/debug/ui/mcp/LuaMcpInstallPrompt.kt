package com.fixcode.intellij.lua.debug.ui.mcp

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.fixcode.intellij.lua.debug.api.LuaMcpClient
import com.fixcode.intellij.lua.debug.api.LuaMcpInstallResult
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugProjectState
import com.fixcode.intellij.lua.lang.LuaFileType
import java.nio.file.Path

/**
 * 「MCP 未安装到本项目」的气泡提示，和「IDE 的 MCP 服务器没开」合成一个，不弹两次：
 *
 * - 时机：项目打开后（项目里有 .lua 文件）、第一次启动 Lua 调试会话时；
 * - 一个 IDE 会话里每个项目最多弹一次，「不再提示」按项目记住；IDE 里没有 MCP Server 插件（被禁用）时不提示；
 * - 判定已安装：任一客户端配置里有 IDE 服务器那一项、且地址指向 IDE 现在的端口；装了但端口不对、
 *   或者还留着旧版的 emmylua-debug 时提示更新；
 * - IDE 的 MCP 服务器没开：没装时一个按钮「打开 MCP 服务器并安装」，先开设置页让用户打开服务器，回来后开了就接着安装；
 *   已经装好时只提示去打开服务器。
 */
object LuaMcpInstallPrompt {
    private val LOG = logger<LuaMcpInstallPrompt>()
    private val SHOWN = Key.create<Boolean>("EmmyLua.Debug.McpPromptShown")
    const val GROUP_ID = "EmmyLua Debug"

    enum class Trigger { PROJECT_OPEN, SESSION_START }

    fun maybeShow(project: Project, trigger: Trigger) {
        if (project.isDisposed || project.getUserData(SHOWN) == true) return
        val service = LuaMcpService.getInstance() ?: return
        if (LuaDebugProjectState.getInstance(project).mcpPromptDismissed) return
        project.putUserData(SHOWN, true)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (trigger == Trigger.PROJECT_OPEN && !hasLuaFiles(project)) {
                    // 没 Lua 文件的项目不打扰；之后真的开 Lua 调试会话时还会再检查
                    project.putUserData(SHOWN, false)
                    return@executeOnPooledThread
                }
                val state = service.serverState()
                if (!state.available) return@executeOnPooledThread
                val root = service.installRoots(project).firstOrNull() ?: return@executeOnPooledThread
                val statuses = service.installStatus(project, root)
                val upToDate = statuses.any { it.installed && !it.outdated }
                val outdated = statuses.filter { it.outdated }.map { it.client }
                if (upToDate && state.running) return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    when {
                        upToDate -> notifyServerStopped(project)
                        else -> notify(project, root, outdated, state.running)
                    }
                }, project.disposed)
            } catch (e: Exception) {
                LOG.info("检查 MCP 安装状态失败", e)
            }
        }
    }

    private fun hasLuaFiles(project: Project): Boolean {
        DumbService.getInstance(project).waitForSmartMode()
        return ReadAction.compute<Boolean, RuntimeException> {
            !project.isDisposed && FileTypeIndex.containsFileOfType(LuaFileType, GlobalSearchScope.projectScope(project))
        }
    }

    /** 配置已经写好，只差服务器没开。 */
    private fun notifyServerStopped(project: Project) {
        val service = LuaMcpService.getInstance() ?: return
        group().createNotification(
            LuaDebugBundle.message("mcp.prompt.stopped.title"),
            LuaDebugBundle.message("mcp.prompt.stopped.content"),
            NotificationType.INFORMATION,
        )
            .addAction(NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.open")) {
                service.openServerSettings(project)
            })
            .addAction(dismissAction(project))
            .notify(project)
    }

    private fun notify(project: Project, root: Path, outdated: List<LuaMcpClient>, running: Boolean) {
        val service = LuaMcpService.getInstance() ?: return
        val stoppedNote = if (running) "" else "<br>" + LuaDebugBundle.message("mcp.prompt.needServer")
        val notification = if (outdated.isEmpty()) {
            group().createNotification(
                LuaDebugBundle.message("mcp.prompt.title"),
                LuaDebugBundle.message("mcp.prompt.content") + stoppedNote,
                NotificationType.INFORMATION,
            ).addAction(NotificationAction.createSimpleExpiring(
                LuaDebugBundle.message(if (running) "mcp.prompt.install" else "mcp.prompt.enableAndInstall", root.toString()),
            ) {
                withServer(project, service, running) { installDefault(project, root) }
            })
        } else {
            group().createNotification(
                LuaDebugBundle.message("mcp.prompt.outdated.title"),
                LuaDebugBundle.message("mcp.prompt.outdated.content", outdated.joinToString(", ") { it.displayName }) + stoppedNote,
                NotificationType.WARNING,
            ).addAction(NotificationAction.createSimpleExpiring(
                LuaDebugBundle.message(if (running) "mcp.prompt.update" else "mcp.prompt.enableAndUpdate"),
            ) {
                withServer(project, service, running) { installInBackground(project, root, outdated) }
            })
        }
        notification
            .addAction(NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.choose")) {
                LuaMcpInstallDialog.show(project)
            })
            .addAction(dismissAction(project))
            .notify(project)
    }

    /**
     * 服务器没开时先打开「设置 → 工具 → MCP 服务器」（模态，用户关掉才返回），开了就接着装；
     * 用户没开就不装，免得写进一个连不上的地址还以为好了，再提示一句。
     */
    private fun withServer(project: Project, service: LuaMcpService, running: Boolean, then: () -> Unit) {
        if (running) return then()
        service.openServerSettings(project)
        if (service.serverState().running) {
            then()
        } else {
            group().createNotification(
                LuaDebugBundle.message("mcp.prompt.stopped.title"),
                LuaDebugBundle.message("mcp.prompt.notInstalledYet"),
                NotificationType.WARNING,
            ).addAction(NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.choose")) {
                LuaMcpInstallDialog.show(project)
            }).notify(project)
        }
    }

    private fun dismissAction(project: Project) =
        NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.dismiss")) {
            LuaDebugProjectState.getInstance(project).mcpPromptDismissed = true
        }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)

    /** 「安装到 <根目录>」：装已检测到目录的客户端；一个都没检测到时装 Claude Code（.mcp.json 是最通用的）。 */
    private fun installDefault(project: Project, root: Path) {
        val service = LuaMcpService.getInstance() ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, LuaDebugBundle.message("mcp.install.progress")) {
            override fun run(indicator: ProgressIndicator) {
                val statuses = service.installStatus(project, root)
                val clients = statuses.filter { (it.detected || it.legacy) && it.client.projectLevel }.map { it.client }
                    .ifEmpty { listOf(LuaMcpClient.CLAUDE_CODE) }
                val results = service.install(project, root, clients)
                reportResults(project, root, results)
            }
        })
    }

    fun installInBackground(project: Project, root: Path, clients: Collection<LuaMcpClient>) {
        val service = LuaMcpService.getInstance() ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, LuaDebugBundle.message("mcp.install.progress")) {
            override fun run(indicator: ProgressIndicator) {
                reportResults(project, root, service.install(project, root, clients))
            }
        })
    }

    /**
     * 写完刷新 VFS，让 IntelliJ 自己的「未加入版本管理的新文件」提示出来（不自动 git add）；
     * 失败项给出要手动粘贴的片段。
     */
    fun reportResults(project: Project, root: Path, results: List<LuaMcpInstallResult>) {
        LocalFileSystem.getInstance().refreshNioFiles(results.map { it.configFile }, true, false, null)
        val ok = results.filter { it.success }
        val failed = results.filterNot { it.success }
        if (ok.isNotEmpty()) {
            val files = ok.joinToString("<br>") { r ->
                val path = runCatching { root.relativize(r.configFile).toString() }.getOrDefault(r.configFile.toString())
                "${r.client.displayName}：$path" + if (r.created) LuaDebugBundle.message("mcp.install.newFile") else ""
            }
            group().createNotification(LuaDebugBundle.message("mcp.install.done"), files, NotificationType.INFORMATION)
                .notify(project)
        }
        failed.forEach { r ->
            val content = buildString {
                append(escape(r.message ?: LuaDebugBundle.message("mcp.install.failed.unknown")))
                r.manualSnippet?.let {
                    append("<br>").append(LuaDebugBundle.message("mcp.install.failed.manual", r.configFile.toString()))
                    append("<pre>").append(escape(it)).append("</pre>")
                }
            }
            val n: Notification = group().createNotification(
                LuaDebugBundle.message("mcp.install.failed", r.client.displayName), content, NotificationType.ERROR,
            )
            r.manualSnippet?.let { snippet ->
                n.addAction(NotificationAction.createSimple(LuaDebugBundle.message("mcp.install.copy")) {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance()
                        .setContents(java.awt.datatransfer.StringSelection(snippet))
                })
            }
            n.notify(project)
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** 项目打开后检查一次。 */
class LuaMcpInstallPromptActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        LuaMcpInstallPrompt.maybeShow(project, LuaMcpInstallPrompt.Trigger.PROJECT_OPEN)
    }
}

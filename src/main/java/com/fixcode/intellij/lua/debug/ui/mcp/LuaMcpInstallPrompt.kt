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
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.fixcode.intellij.lua.lang.LuaFileType
import java.nio.file.Path

/**
 * "MCP 未安装到本项目"的气泡提示（设计文档 6.5）：
 *
 * - 时机：项目打开后（项目里有 .lua 文件）、第一次启动 Lua 调试会话时；
 * - 一个 IDE 会话里每个项目最多弹一次，"不再提示"按项目记住；
 * - 判定已安装：任一客户端配置里有 emmylua-debug 且地址指向当前端口；装了但端口不对时提示更新。
 */
object LuaMcpInstallPrompt {
    private val LOG = logger<LuaMcpInstallPrompt>()
    private val SHOWN = Key.create<Boolean>("EmmyLua.Debug.McpPromptShown")
    const val GROUP_ID = "EmmyLua Debug"

    enum class Trigger { PROJECT_OPEN, SESSION_START }

    fun maybeShow(project: Project, trigger: Trigger) {
        if (project.isDisposed || project.getUserData(SHOWN) == true) return
        val service = LuaMcpService.getInstance() ?: return
        if (!LuaDebugSettings.getInstance().mcpEnabled) return
        if (LuaDebugProjectState.getInstance(project).mcpPromptDismissed) return
        project.putUserData(SHOWN, true)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (trigger == Trigger.PROJECT_OPEN && !hasLuaFiles(project)) {
                    // 没 Lua 文件的项目不打扰；之后真的开 Lua 调试会话时还会再检查
                    project.putUserData(SHOWN, false)
                    return@executeOnPooledThread
                }
                val root = service.installRoots(project).firstOrNull() ?: return@executeOnPooledThread
                val statuses = service.installStatus(project, root)
                val upToDate = statuses.any { it.installed && !it.outdated }
                val outdated = statuses.filter { it.installed && it.outdated }
                if (upToDate) return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) notify(project, root, outdated.map { it.client })
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

    private fun notify(project: Project, root: Path, outdated: List<LuaMcpClient>) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        val notification = if (outdated.isEmpty()) {
            group.createNotification(
                LuaDebugBundle.message("mcp.prompt.title"),
                LuaDebugBundle.message("mcp.prompt.content"),
                NotificationType.INFORMATION,
            ).addAction(NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.install", root.toString())) {
                installDefault(project, root)
            })
        } else {
            group.createNotification(
                LuaDebugBundle.message("mcp.prompt.outdated.title"),
                LuaDebugBundle.message("mcp.prompt.outdated.content", outdated.joinToString(", ") { it.displayName }),
                NotificationType.WARNING,
            ).addAction(NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.update")) {
                installInBackground(project, root, outdated)
            })
        }
        notification
            .addAction(NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.choose")) {
                LuaMcpInstallDialog.show(project)
            })
            .addAction(NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.dismiss")) {
                LuaDebugProjectState.getInstance(project).mcpPromptDismissed = true
            })
            .notify(project)
    }

    /** "安装到 <根目录>"：装已检测到目录的客户端；一个都没检测到时装 Claude Code（.mcp.json 是最通用的）。 */
    private fun installDefault(project: Project, root: Path) {
        val service = LuaMcpService.getInstance() ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, LuaDebugBundle.message("mcp.install.progress")) {
            override fun run(indicator: ProgressIndicator) {
                val statuses = service.installStatus(project, root)
                val clients = statuses.filter { it.detected && it.client.projectLevel }.map { it.client }
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
     * 写完刷新 VFS，让 IntelliJ 自己的"未加入版本管理的新文件"提示出来（设计文档：不自动 git add）；
     * 失败项给出要手动粘贴的片段。
     */
    fun reportResults(project: Project, root: Path, results: List<LuaMcpInstallResult>) {
        LocalFileSystem.getInstance().refreshNioFiles(results.map { it.configFile }, true, false, null)
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        val ok = results.filter { it.success }
        val failed = results.filterNot { it.success }
        if (ok.isNotEmpty()) {
            val files = ok.joinToString("<br>") { r ->
                val path = runCatching { root.relativize(r.configFile).toString() }.getOrDefault(r.configFile.toString())
                "${r.client.displayName}：$path" + if (r.created) LuaDebugBundle.message("mcp.install.newFile") else ""
            }
            group.createNotification(LuaDebugBundle.message("mcp.install.done"), files, NotificationType.INFORMATION)
                .notify(project)
        }
        failed.forEach { r ->
            val content = buildString {
                append(r.message ?: LuaDebugBundle.message("mcp.install.failed.unknown"))
                r.manualSnippet?.let {
                    append("<br>").append(LuaDebugBundle.message("mcp.install.failed.manual", r.configFile.toString()))
                    append("<pre>").append(escape(it)).append("</pre>")
                }
            }
            val n: Notification = group.createNotification(
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

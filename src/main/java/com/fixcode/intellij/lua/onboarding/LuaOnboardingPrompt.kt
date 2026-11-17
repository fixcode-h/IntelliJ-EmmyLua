package com.fixcode.intellij.lua.onboarding

import com.fixcode.intellij.lua.debug.ui.mcp.LuaMcpInstallPrompt
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugProjectState
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil

/**
 * 打开 UE 项目（往上有 .uproject、项目里有 .lua）时，有待处理的项就弹一个「EmmyLua 上手检查」气泡，
 * 代替原来分散的首次提示（MCP 安装气泡在 UE 项目里不再单独弹，见 [LuaMcpInstallPrompt.maybeShow]）。
 *
 * - 一个 IDE 会话里每个项目最多弹一次；都就绪了不弹；
 * - 点「打开上手检查」（看过了）或「不再提示」后这个项目不再主动弹，按项目记在 [LuaDebugProjectState.onboardingDismissed]；
 *   「不再提示」同时关掉 MCP 的单独提示，免得开调试会话时又冒出来。随时可从「工具 → EmmyLua 上手检查」打开。
 */
object LuaOnboardingPrompt {
    private val LOG = logger<LuaOnboardingPrompt>()
    private val SHOWN = Key.create<Boolean>("EmmyLua.OnboardingPromptShown")
    const val GROUP_ID = "EmmyLua Onboarding"

    fun maybeShow(project: Project) {
        if (project.isDisposed || project.getUserData(SHOWN) == true) return
        if (!LuaOnboarding.isUnrealProject(project)) return
        if (LuaDebugProjectState.getInstance(project).onboardingDismissed) return
        project.putUserData(SHOWN, true)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (!LuaMcpInstallPrompt.hasLuaFiles(project)) return@executeOnPooledThread
                val todo = LuaOnboarding.check(project).filter { it.status == LuaOnboarding.Status.TODO }
                if (todo.isEmpty()) return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) notify(project, todo)
                }, project.disposed)
            } catch (e: Exception) {
                LOG.info("上手检查失败", e)
            }
        }
    }

    private fun notify(project: Project, todo: List<LuaOnboarding.Item>) {
        // MCP 是其中一项，本次会话不再单独弹 MCP 气泡
        LuaMcpInstallPrompt.markShown(project)
        val content = "这个 UE 项目还有 ${todo.size} 项没准备好：<br>" +
            todo.joinToString("<br>") { "· " + StringUtil.escapeXmlEntities(it.title) }
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification("EmmyLua 上手检查", content, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("打开上手检查") { LuaOnboardingDialog.show(project) })
            .addAction(NotificationAction.createSimpleExpiring("不再提示") {
                val state = LuaDebugProjectState.getInstance(project)
                state.onboardingDismissed = true
                state.mcpPromptDismissed = true
            })
            .notify(project)
    }
}

class LuaOnboardingPromptActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        LuaOnboardingPrompt.maybeShow(project)
    }
}

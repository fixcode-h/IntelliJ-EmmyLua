package com.fixcode.intellij.lua.debug.mcp

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.fixcode.intellij.lua.debugger.mcp.protocol.ControlDecision
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpConfirmer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 「Agent 请求控制…」的确认：右下角气泡通知 + 按钮（不用模态对话框，不打断用户；设计文档 6.2）。
 *
 * 调用线程（MCP 的 HTTP 工作线程）阻塞等待回答，最多 [ANSWER_TIMEOUT_SECONDS] 秒；超时当作没回答，
 * Agent 收到 permission_denied 并被告知去提醒用户。
 */
class IdeMcpConfirmer : McpConfirmer {

    override fun confirmControl(subject: String, clientName: String?): ControlDecision {
        val agent = clientName?.takeIf { it.isNotBlank() } ?: "Agent"
        val answer = ask(
            title = "$agent 请求控制 Lua 调试",
            content = "$agent 想控制「$subject」（下断点、继续、单步）。",
            options = listOf(
                "本次会话总是允许" to ControlDecision.ALLOW_SESSION,
                "允许一次" to ControlDecision.ALLOW_ONCE,
                "拒绝" to ControlDecision.DENY,
            ),
            type = NotificationType.WARNING,
        )
        return answer ?: ControlDecision.NO_ANSWER
    }

    override fun confirmLaunch(description: String, clientName: String?): Boolean {
        val agent = clientName?.takeIf { it.isNotBlank() } ?: "Agent"
        return ask(
            title = "$agent 请求开始调试",
            content = "$agent 想$description。",
            options = listOf("允许" to true, "拒绝" to false),
            type = NotificationType.WARNING,
        ) == true
    }

    override fun luaEvalAllowed(): Boolean = LuaDebugSettings.getInstance().allowAgentLuaEval

    private fun <T> ask(title: String, content: String, options: List<Pair<String, T>>, type: NotificationType): T? {
        if (ApplicationManager.getApplication().isDispatchThread) {
            // 不会发生：MCP 请求都在自己的线程上。真发生了宁可拒绝也不能卡死 EDT
            return null
        }
        val answer = CompletableFuture<T?>()
        var notification: Notification? = null
        ApplicationManager.getApplication().invokeLater {
            val n = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, content, type)
            for ((label, value) in options) {
                n.addAction(NotificationAction.createSimpleExpiring(label) { answer.complete(value) })
            }
            // 用户关掉气泡 = 没回答
            n.whenExpired { answer.complete(null) }
            notification = n
            n.notify(targetProject())
        }
        return try {
            answer.get(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            null
        } finally {
            ApplicationManager.getApplication().invokeLater { notification?.expire() }
        }
    }

    /** 通知显示在当前有焦点的项目窗口；没有就显示在第一个打开的项目里。 */
    private fun targetProject(): Project? {
        val focused = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        if (focused != null && !focused.isDisposed) return focused
        return ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && WindowManager.getInstance().getFrame(it) != null }
    }

    companion object {
        /** 由 UI 线程在 emmylua-debug.xml 里注册。 */
        const val NOTIFICATION_GROUP = "EmmyLua Debug"
        const val ANSWER_TIMEOUT_SECONDS = 60L
    }
}

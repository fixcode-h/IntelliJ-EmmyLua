package com.fixcode.intellij.lua.debug.ui.mcp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.fixcode.intellij.lua.debug.api.LuaMcpServerState
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.LuaDebugIcons
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugConfigurable
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * 右下角「Lua 调试 MCP ●」（设计文档 10.6）：颜色表示服务状态，未装到本项目时带提示点；
 * 点开是一个小菜单：端口、已连客户端、安装到本项目、设置。
 */
class LuaMcpStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = LuaDebugBundle.message("statusbar.name")
    override fun isAvailable(project: Project): Boolean = LuaMcpService.getInstance() != null
    override fun createWidget(project: Project): StatusBarWidget = LuaMcpStatusBarWidget(project)

    companion object {
        const val ID = "EmmyLuaDebugMcp"
    }
}

private class LuaMcpStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel().apply { border = JBUI.Borders.empty(0, 4) }
    /** 本项目有没有装上；null = 还没查过。查一次要读文件，放后台，结果缓存到下次状态变化。 */
    @Volatile private var installedInProject: Boolean? = null

    init {
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showPopup(e)
        })
    }

    override fun ID(): String = LuaMcpStatusBarWidgetFactory.ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        val service = LuaMcpService.getInstance() ?: return
        service.addListener({
            installedInProject = null
            ApplicationManager.getApplication().invokeLater({ update() }, project.disposed)
        }, this)
        update()
    }

    private fun update() {
        val service = LuaMcpService.getInstance() ?: return
        val state = service.serverState()
        val installed = installedInProject
        if (installed == null) refreshInstalled(service)
        label.text = LuaDebugBundle.message("statusbar.text")
        label.icon = when {
            state.error != null -> LuaDebugIcons.MCP_ERROR
            !state.enabled || !state.running -> LuaDebugIcons.MCP_STOPPED
            installed == false -> LuaDebugIcons.MCP_NOT_INSTALLED
            else -> LuaDebugIcons.MCP_RUNNING
        }
        label.toolTipText = tooltip(state, installed)
    }

    private fun refreshInstalled(service: LuaMcpService) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val installed = runCatching {
                val root = service.installRoots(project).firstOrNull() ?: return@runCatching false
                service.installStatus(project, root).any { it.installed && !it.outdated }
            }.getOrDefault(false)
            installedInProject = installed
            ApplicationManager.getApplication().invokeLater({ update() }, project.disposed)
        }
    }

    private fun tooltip(state: LuaMcpServerState, installed: Boolean?): String = buildString {
        append("<html>")
        append(
            when {
                state.error != null -> LuaDebugBundle.message("statusbar.tooltip.error", state.error)
                !state.enabled -> LuaDebugBundle.message("statusbar.tooltip.disabled")
                !state.running -> LuaDebugBundle.message("statusbar.tooltip.stopped")
                else -> LuaDebugBundle.message("statusbar.tooltip.running", state.url, state.connectedClients)
            }
        )
        if (installed == false) append("<br>").append(LuaDebugBundle.message("statusbar.tooltip.notInstalled"))
        append("</html>")
    }

    private fun showPopup(e: MouseEvent) {
        val service = LuaMcpService.getInstance() ?: return
        val state = service.serverState()
        val group = DefaultActionGroup()
        group.add(info(
            if (state.running) LuaDebugBundle.message("statusbar.popup.running", state.port, state.connectedClients)
            else LuaDebugBundle.message("statusbar.popup.stopped")
        ))
        group.add(Separator.getInstance())
        group.add(DumbAwareAction.create(LuaDebugBundle.message("statusbar.popup.install")) { LuaMcpInstallDialog.show(project) })
        group.add(DumbAwareAction.create(LuaDebugBundle.message("statusbar.popup.copyUrl")) {
            com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(state.url))
        })
        group.add(DumbAwareAction.create(LuaDebugBundle.message("statusbar.popup.settings")) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, LuaDebugConfigurable::class.java)
        })
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            LuaDebugBundle.message("statusbar.name"), group,
            com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
        )
        val size = popup.content.preferredSize
        popup.show(RelativePoint(e.component, Point(0, -size.height)))
    }

    /** 弹出菜单里的只读信息行。 */
    private fun info(text: String) = object : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) {}
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = false }
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    /** 监听器挂在 this 上，状态栏释放这个部件时一起释放。 */
    override fun dispose() {}
}

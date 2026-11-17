package com.fixcode.intellij.lua.debug.ui.session

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.fixcode.intellij.lua.debug.api.LuaDebugSessionModel
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.api.LuaProfilerModel
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.LuaDebugIcons
import javax.swing.JComponent

/**
 * 在标准 Debug 工具窗口里加「VM」「映射」「Lua 采样」「Agent」页签（设计文档 10.4）。
 *
 * 页签由 UI 画，数据来自引擎 XDebugProcess 实现的 [LuaDebugSessionModel] 和 MCP 服务；
 * 引擎没实现这个接口时只加 Agent 页，MCP 没装时不加 Agent 页。
 */
object LuaDebugSessionTabs {

    fun install(project: Project, session: XDebugSession) {
        val ui = session.ui ?: return
        // 会话结束时一起释放页签里的监听器
        val disposable = Disposer.newCheckedDisposable("EmmyLua debug tabs")
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionStopped() = Disposer.dispose(disposable)
        }, disposable)
        installFramesLayout(session, ui.component, disposable)

        val model = session.debugProcess as? LuaDebugSessionModel
        if (model != null) {
            add(ui, "EmmyLua.VMs", LuaDebugBundle.message("tab.vm"), LuaDebugIcons.LUA,
                LuaVmPanel(model, disposable).component)
            add(ui, "EmmyLua.Mapping", LuaDebugBundle.message("tab.mapping"), AllIcons.Nodes.Folder,
                LuaMappingPanel(project, model, disposable).component)
        }
        val profiler = session.debugProcess as? LuaProfilerModel
        if (profiler != null) {
            add(ui, "EmmyLua.Profiler", LuaDebugBundle.message("tab.profiler"), AllIcons.Actions.Profile,
                LuaProfilerPanel(project, profiler, disposable).component)
        }
        val mcp = LuaMcpService.getInstance()
        if (mcp != null) {
            add(ui, "EmmyLua.Agent", LuaDebugBundle.message("tab.agent"), AllIcons.Nodes.Plugin,
                LuaAgentPanel(project, mcp, disposable).component)
        }
    }

    /** 调用栈列表两端对齐；帧页有时到第一次暂停才建好，没找到就每次暂停再试，装上为止。 */
    private fun installFramesLayout(session: XDebugSession, root: JComponent, disposable: com.intellij.openapi.util.CheckedDisposable) {
        if (LuaFramesListLayout.install(root, disposable)) return
        var done = false
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                ApplicationManager.getApplication().invokeLater {
                    if (!done && !disposable.isDisposed) done = LuaFramesListLayout.install(root, disposable)
                }
            }
        }, disposable)
    }

    private fun add(ui: com.intellij.execution.ui.RunnerLayoutUi, id: String, title: String, icon: javax.swing.Icon, component: JComponent) {
        if (ui.findContent(id) != null) return
        val content = ui.createContent(id, component, title, icon, null)
        content.isCloseable = false
        ui.addContent(content)
    }
}

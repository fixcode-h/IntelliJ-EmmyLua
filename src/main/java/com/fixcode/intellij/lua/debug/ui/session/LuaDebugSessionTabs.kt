package com.fixcode.intellij.lua.debug.ui.session

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.fixcode.intellij.lua.debug.api.LuaDebugSessionModel
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.LuaDebugIcons
import javax.swing.JComponent

/**
 * 在标准 Debug 工具窗口里加「VM」「Agent」「映射」三个页签（设计文档 10.4）。
 *
 * 页签由 UI 画，数据来自引擎 XDebugProcess 实现的 [LuaDebugSessionModel] 和 MCP 服务；
 * 引擎没实现这个接口时只加 Agent 页，MCP 没装时不加 Agent 页。
 */
object LuaDebugSessionTabs {

    fun install(project: Project, session: XDebugSession) {
        val ui = session.ui ?: return
        // 会话结束时一起释放页签里的监听器
        val disposable = Disposer.newDisposable("EmmyLua debug tabs")
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionStopped() = Disposer.dispose(disposable)
        }, disposable)

        val model = session.debugProcess as? LuaDebugSessionModel
        if (model != null) {
            add(ui, "EmmyLua.VMs", LuaDebugBundle.message("tab.vm"), LuaDebugIcons.LUA,
                LuaVmPanel(model, disposable).component)
            add(ui, "EmmyLua.Mapping", LuaDebugBundle.message("tab.mapping"), AllIcons.Nodes.Folder,
                LuaMappingPanel(project, model, disposable).component)
        }
        val mcp = LuaMcpService.getInstance()
        if (mcp != null) {
            add(ui, "EmmyLua.Agent", LuaDebugBundle.message("tab.agent"), AllIcons.Nodes.Plugin,
                LuaAgentPanel(project, mcp, disposable).component)
        }
    }

    private fun add(ui: com.intellij.execution.ui.RunnerLayoutUi, id: String, title: String, icon: javax.swing.Icon, component: JComponent) {
        if (ui.findContent(id) != null) return
        val content = ui.createContent(id, component, title, icon, null)
        content.isCloseable = false
        ui.addContent(content)
    }
}

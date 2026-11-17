package com.fixcode.intellij.lua.debug.ui.mcp

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.fixcode.intellij.lua.debug.api.LuaMcpClient
import com.fixcode.intellij.lua.debug.api.LuaMcpClientStatus
import com.fixcode.intellij.lua.debug.api.LuaMcpServerState
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import java.awt.event.ActionEvent
import java.nio.file.Path
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * 「安装 MCP 到项目…」：选根目录、勾客户端，显示每个客户端的当前状态。
 * 写进去的是 IDE 自带 MCP 服务器的地址和它「自动配置」用的条目名；服务器没开时顶上一行提示并给「打开」按钮，
 * 打开回来后刷新地址和状态（先开服务器再装，一个弹窗里做完）。
 * 默认勾选已安装的和已检测到目录的；Codex 写用户级配置，单独标注。
 */
class LuaMcpInstallDialog private constructor(
    private val project: Project,
    private val service: LuaMcpService,
    roots: List<Path>,
) : DialogWrapper(project, true) {

    private val rootCombo = ComboBox(DefaultComboBoxModel(roots.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.toString() }
    }
    private val endpointLabel = JBLabel()
    private val serverWarning = JBLabel().apply { icon = AllIcons.General.Warning }
    private val notes = JBLabel().apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL }
    private val checks = LuaMcpClient.entries.associateWith { JBCheckBox(it.displayName) }
    private val statusLabels = LuaMcpClient.entries.associateWith { JBLabel().apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL } }
    private var statuses: List<LuaMcpClientStatus> = emptyList()
    private var userTouched = false
    /** 「IDE 的 MCP 服务器没开」那一行，服务器开了就藏起来。 */
    private var serverRow: Row? = null

    private val uninstallAction = object : DialogWrapperAction(LuaDebugBundle.message("mcp.dialog.uninstall")) {
        override fun doAction(e: ActionEvent) = uninstallSelected()
    }

    init {
        title = LuaDebugBundle.message("mcp.dialog.title")
        setOKButtonText(LuaDebugBundle.message("mcp.dialog.install"))
        rootCombo.addActionListener { loadStatus() }
        checks.values.forEach { c -> c.addActionListener { userTouched = true } }
        init()
        loadStatus()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(endpointLabel) }
        serverRow = row {
            cell(serverWarning)
            button(LuaDebugBundle.message("mcp.prompt.open")) {
                service.openServerSettings(project)
                loadStatus()
            }
        }
        row { cell(notes) }
        row(LuaDebugBundle.message("mcp.dialog.root")) {
            cell(rootCombo).align(AlignX.FILL)
                .comment(LuaDebugBundle.message("mcp.dialog.root.comment"))
        }
        group(LuaDebugBundle.message("mcp.dialog.clients")) {
            LuaMcpClient.entries.forEach { client ->
                row {
                    cell(checks.getValue(client))
                    cell(statusLabels.getValue(client))
                }
                if (!client.projectLevel) {
                    row { comment(LuaDebugBundle.message("mcp.dialog.global")) }
                }
            }
        }
        row { comment(LuaDebugBundle.message("mcp.dialog.safe")) }
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(uninstallAction)

    private fun selectedRoot(): Path? = rootCombo.selectedItem as? Path

    private fun selectedClients(): List<LuaMcpClient> = checks.filterValues { it.isSelected }.keys.toList()

    private fun showServer(state: LuaMcpServerState) {
        endpointLabel.text = LuaDebugBundle.message("mcp.dialog.endpoint", state.url ?: "", state.serverName ?: "")
        serverWarning.text = LuaDebugBundle.message("mcp.dialog.serverStopped")
        serverRow?.visible(!state.running)
        val lines = buildList {
            if (state.guessed) add(LuaDebugBundle.message("mcp.dialog.guessed"))
            state.authHeader?.let { add(LuaDebugBundle.message("mcp.dialog.token", it)) }
        }
        notes.text = if (lines.isEmpty()) "" else "<html>" + lines.joinToString("<br>") + "</html>"
        notes.isVisible = lines.isNotEmpty()
    }

    private fun loadStatus() {
        showServer(service.serverState())
        val root = selectedRoot() ?: return
        statusLabels.values.forEach { it.text = LuaDebugBundle.message("mcp.dialog.loading"); it.icon = null }
        val modality = ModalityState.stateForComponent(rootCombo)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service.installStatus(project, root) }
            ApplicationManager.getApplication().invokeLater({ showStatus(result) }, modality)
        }
    }

    private fun showStatus(result: Result<List<LuaMcpClientStatus>>) {
        statuses = result.getOrElse { e ->
            statusLabels.values.forEach { it.text = e.message ?: ""; it.icon = AllIcons.General.Error }
            return
        }
        for (s in statuses) {
            val label = statusLabels[s.client] ?: continue
            val (icon, text) = when {
                s.error != null -> AllIcons.General.Error to s.error
                s.legacy -> AllIcons.General.Warning to LuaDebugBundle.message("mcp.status.legacy")
                s.installed && s.outdated -> AllIcons.General.Warning to LuaDebugBundle.message("mcp.status.outdated")
                s.installed -> AllIcons.General.InspectionsOK to LuaDebugBundle.message("mcp.status.installed")
                s.detected -> null to LuaDebugBundle.message("mcp.status.detected")
                else -> null to LuaDebugBundle.message("mcp.status.notInstalled")
            }
            label.icon = icon
            label.text = "$text · ${s.configFile}"
            // Codex 是全局配置，只在用户自己勾的时候写，不默认勾；用户已经改过勾选就不再覆盖
            if (!userTouched) checks[s.client]?.isSelected = s.client.projectLevel && (s.installed || s.detected || s.legacy)
        }
        if (!userTouched && checks.values.none { it.isSelected }) checks[LuaMcpClient.CLAUDE_CODE]?.isSelected = true
    }

    override fun doOKAction() {
        val root = selectedRoot() ?: return
        val clients = selectedClients()
        if (clients.isEmpty()) {
            Messages.showWarningDialog(contentPanel, LuaDebugBundle.message("mcp.dialog.noneSelected"), title)
            return
        }
        super.doOKAction()
        LuaMcpInstallPrompt.installInBackground(project, root, clients)
    }

    private fun uninstallSelected() {
        val root = selectedRoot() ?: return
        val clients = selectedClients().filter { c -> statuses.any { it.client == c && (it.installed || it.legacy) } }
        if (clients.isEmpty()) {
            Messages.showInfoMessage(contentPanel, LuaDebugBundle.message("mcp.dialog.nothingToUninstall"), title)
            return
        }
        close(CANCEL_EXIT_CODE)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, LuaDebugBundle.message("mcp.uninstall.progress")) {
            override fun run(indicator: ProgressIndicator) {
                LuaMcpInstallPrompt.reportResults(project, root, service.uninstall(project, root, clients))
            }
        })
    }

    companion object {
        fun show(project: Project) {
            val service = LuaMcpService.getInstance()
            if (service == null) {
                Messages.showWarningDialog(project, LuaDebugBundle.message("mcp.error.noService"), LuaDebugBundle.message("mcp.dialog.title"))
                return
            }
            if (!service.serverState().available) {
                Messages.showWarningDialog(project, LuaDebugBundle.message("mcp.state.unavailable"), LuaDebugBundle.message("mcp.dialog.title"))
                return
            }
            // installRoots 读 VCS 配置，很快，直接在 EDT 上调
            val roots = service.installRoots(project)
            if (roots.isEmpty()) {
                Messages.showWarningDialog(project, LuaDebugBundle.message("mcp.error.noRoot"), LuaDebugBundle.message("mcp.dialog.title"))
                return
            }
            LuaMcpInstallDialog(project, service, roots).show()
        }
    }
}

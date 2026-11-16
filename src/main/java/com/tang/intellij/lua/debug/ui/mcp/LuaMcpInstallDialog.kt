/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.debug.ui.mcp

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
import com.intellij.ui.dsl.builder.panel
import com.tang.intellij.lua.debug.api.LuaMcpClient
import com.tang.intellij.lua.debug.api.LuaMcpClientStatus
import com.tang.intellij.lua.debug.api.LuaMcpService
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import java.awt.event.ActionEvent
import java.nio.file.Path
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * 「安装 MCP 到项目…」：选根目录、勾客户端，显示每个客户端的当前状态（设计文档 6.5）。
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
    private val checks = LuaMcpClient.entries.associateWith { JBCheckBox(it.displayName) }
    private val statusLabels = LuaMcpClient.entries.associateWith { JBLabel().apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL } }
    private var statuses: List<LuaMcpClientStatus> = emptyList()

    private val uninstallAction = object : DialogWrapperAction(LuaDebugBundle.message("mcp.dialog.uninstall")) {
        override fun doAction(e: ActionEvent) = uninstallSelected()
    }

    init {
        title = LuaDebugBundle.message("mcp.dialog.title")
        setOKButtonText(LuaDebugBundle.message("mcp.dialog.install"))
        rootCombo.addActionListener { loadStatus() }
        init()
        loadStatus()
    }

    override fun createCenterPanel(): JComponent = panel {
        val state = service.serverState()
        row {
            label(LuaDebugBundle.message("mcp.dialog.endpoint", state.url))
        }
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

    private fun loadStatus() {
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
                s.installed && s.outdated -> AllIcons.General.Warning to LuaDebugBundle.message("mcp.status.outdated")
                s.installed -> AllIcons.General.InspectionsOK to LuaDebugBundle.message("mcp.status.installed")
                s.detected -> null to LuaDebugBundle.message("mcp.status.detected")
                else -> null to LuaDebugBundle.message("mcp.status.notInstalled")
            }
            label.icon = icon
            label.text = "$text · ${s.configFile}"
            // Codex 是全局配置，只在用户自己勾的时候写，不默认勾
            checks[s.client]?.isSelected = s.client.projectLevel && (s.installed || s.detected)
        }
        if (checks.values.none { it.isSelected }) checks[LuaMcpClient.CLAUDE_CODE]?.isSelected = true
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
        val clients = selectedClients().filter { c -> statuses.any { it.client == c && it.installed } }
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

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

package com.tang.intellij.lua.debug.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.layout.selected
import com.tang.intellij.lua.debug.api.LuaMcpService
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import com.tang.intellij.lua.debug.ui.mcp.LuaMcpInstallDialog
import javax.swing.JCheckBox

/**
 * `Settings > Tools > EmmyLua 调试`（设计文档 10.5）：MCP、设备、日志三段。
 * 用 Kotlin UI DSL v2 绑定到 [LuaDebugSettings]，应用时通知 MCP 服务按新端口重启。
 */
class LuaDebugConfigurable : BoundConfigurable(LuaDebugBundle.message("settings.title")) {
    private val settings get() = LuaDebugSettings.getInstance()
    private val mcpState = JBLabel()
    private val installState = JBLabel()

    override fun createPanel(): DialogPanel = panel {
        val mcp = LuaMcpService.getInstance()
        group(LuaDebugBundle.message("settings.mcp")) {
            lateinit var enabled: JCheckBox
            row {
                enabled = checkBox(LuaDebugBundle.message("settings.mcp.enabled"))
                    .bindSelected(settings::mcpEnabled).component
                label(LuaDebugBundle.message("settings.mcp.port"))
                intTextField(1024..65535).columns(6).bindIntText(settings::mcpPort)
                    .enabledIf(enabled.selected)
                cell(mcpState)
            }
            row {
                checkBox(LuaDebugBundle.message("settings.mcp.allowLua"))
                    .bindSelected(settings::allowAgentLuaEval)
                    .comment(LuaDebugBundle.message("settings.mcp.allowLua.comment"))
            }
            row(LuaDebugBundle.message("settings.mcp.installState")) {
                cell(installState)
                button(LuaDebugBundle.message("settings.mcp.install")) {
                    val project = ProjectUtil.getActiveProject() ?: return@button
                    LuaMcpInstallDialog.show(project)
                    refreshInstallState()
                }.enabled(mcp != null)
            }
            if (mcp == null) {
                row { comment(LuaDebugBundle.message("mcp.error.noService")) }
            }
        }
        group(LuaDebugBundle.message("settings.device")) {
            row(LuaDebugBundle.message("settings.device.adb")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                        .withTitle(LuaDebugBundle.message("settings.device.adb.choose")),
                ).align(AlignX.FILL).bindText(settings::adbPath)
                    .comment(LuaDebugBundle.message("settings.device.adb.comment"))
            }
            row(LuaDebugBundle.message("settings.device.symbols")) {
                textField().align(AlignX.FILL)
                    .bindText({ settings.symbolSearchDirs.joinToString(";") }, { v -> settings.symbolSearchDirs = v.split(';') })
                    .comment(LuaDebugBundle.message("settings.device.symbols.comment"))
            }
        }
        group(LuaDebugBundle.message("settings.view")) {
            // 编辑期间改的是这份副本，apply 时整体写回，避免每个控件各自改 state。
            var view = settings.variableView
            // reset 回调按注册顺序执行，必须先于控件读取 view，所以放在最前面。
            onReset { view = settings.variableView }
            row(LuaDebugBundle.message("settings.view.sort")) {
                comboBox(EnumComboBoxModel(LuaVariableSort::class.java),
                    SimpleListCellRenderer.create("") { LuaDebugBundle.message("settings.view.sort.${it.name.lowercase()}") })
                    .bindItem({ view.sort }, { v -> view = view.copy(sort = v ?: LuaVariableSort.SMART) })
            }
            row {
                checkBox(LuaDebugBundle.message("settings.view.groupMethods"))
                    .bindSelected({ view.groupMethods }, { v -> view = view.copy(groupMethods = v) })
                checkBox(LuaDebugBundle.message("settings.view.showInherited"))
                    .bindSelected({ view.showInherited }, { v -> view = view.copy(showInherited = v) })
                checkBox(LuaDebugBundle.message("settings.view.showMetatable"))
                    .bindSelected({ view.showMetatable }, { v -> view = view.copy(showMetatable = v) })
            }
            row {
                checkBox(LuaDebugBundle.message("settings.view.hideStdGlobals"))
                    .bindSelected({ view.hideStdGlobals }, { v -> view = view.copy(hideStdGlobals = v) })
                checkBox(LuaDebugBundle.message("settings.view.showTemporaries"))
                    .bindSelected({ view.showTemporaries }, { v -> view = view.copy(showTemporaries = v) })
                checkBox(LuaDebugBundle.message("settings.view.hexIntegers"))
                    .bindSelected({ view.hexIntegers }, { v -> view = view.copy(hexIntegers = v) })
            }
            row(LuaDebugBundle.message("settings.view.toString")) {
                comboBox(EnumComboBoxModel(LuaToStringPolicy::class.java),
                    SimpleListCellRenderer.create("") { LuaDebugBundle.message("settings.view.toString.${it.name.lowercase()}") })
                    .bindItem({ view.toString }, { v -> view = view.copy(toString = v ?: LuaToStringPolicy.TABLES) })
                    .comment(LuaDebugBundle.message("settings.view.toString.comment"))
            }
            row(LuaDebugBundle.message("settings.view.pageSize")) {
                intTextField(VariableViewSettings.PAGE_SIZE_RANGE).columns(5)
                    .bindIntText({ view.pageSize }, { v -> view = view.copy(pageSize = v) })
                label(LuaDebugBundle.message("settings.view.maxValueLength"))
                intTextField(VariableViewSettings.MAX_VALUE_LENGTH_RANGE).columns(5)
                    .bindIntText({ view.maxValueLength }, { v -> view = view.copy(maxValueLength = v) })
            }
            row(LuaDebugBundle.message("settings.view.rulesFile")) {
                textField().align(AlignX.FILL)
                    .bindText({ view.rulesFile }, { v -> view = view.copy(rulesFile = v) })
                    .comment(LuaDebugBundle.message("settings.view.rulesFile.comment"))
            }
            onApply { settings.variableView = view }
        }
        group(LuaDebugBundle.message("settings.log")) {
            row(LuaDebugBundle.message("settings.log.level")) {
                comboBox(EnumComboBoxModel(LuaDebugLogLevel::class.java),
                    SimpleListCellRenderer.create("") { LuaDebugBundle.message("settings.log.level.${it.name.lowercase()}") })
                    .bindItem(settings::logLevel.toNullableProperty())
            }
        }
        onApply { LuaMcpService.getInstance()?.applySettings(); refreshMcpState() }
        refreshMcpState()
        refreshInstallState()
    }

    private fun refreshMcpState() {
        val state = LuaMcpService.getInstance()?.serverState()
        mcpState.icon = when {
            state == null -> null
            state.error != null -> AllIcons.General.Error
            state.running -> AllIcons.General.InspectionsOK
            else -> AllIcons.General.InspectionsPause
        }
        mcpState.text = when {
            state == null -> ""
            state.error != null -> state.error
            state.running -> LuaDebugBundle.message("settings.mcp.state.running", state.port, state.connectedClients)
            state.enabled -> LuaDebugBundle.message("settings.mcp.state.stopped")
            else -> LuaDebugBundle.message("settings.mcp.state.disabled")
        }
    }

    /** "本项目安装状态: Claude Code ✓  Cursor ✗ …"，读文件放后台。 */
    private fun refreshInstallState() {
        val service = LuaMcpService.getInstance() ?: return
        val project = ProjectUtil.getActiveProject() ?: run {
            installState.text = LuaDebugBundle.message("settings.mcp.noProject")
            return
        }
        installState.text = LuaDebugBundle.message("mcp.dialog.loading")
        val modality = ModalityState.stateForComponent(installState)
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = runCatching {
                val root = service.installRoots(project).firstOrNull() ?: return@runCatching ""
                service.installStatus(project, root).joinToString("  ") { s ->
                    s.client.displayName + " " + when {
                        s.installed && s.outdated -> "⚠"
                        s.installed -> "✓"
                        else -> "✗"
                    }
                }
            }.getOrElse { it.message.orEmpty() }
            ApplicationManager.getApplication().invokeLater({ installState.text = text }, modality)
        }
    }
}

/** Tools > EmmyLua 调试 > 设置… */
class OpenLuaDebugSettingsAction : DumbAwareAction(LuaDebugBundle.message("action.settings")) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, LuaDebugConfigurable::class.java)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

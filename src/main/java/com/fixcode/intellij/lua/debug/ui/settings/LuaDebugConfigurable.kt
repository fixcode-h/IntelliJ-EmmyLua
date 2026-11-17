package com.fixcode.intellij.lua.debug.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
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
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.mcp.LuaMcpInstallDialog
import com.fixcode.intellij.lua.debug.ui.mcp.LuaMcpStatusText
import com.fixcode.intellij.lua.project.SettingsJump

/**
 * `Settings > Tools > EmmyLua 调试`（设计文档 10.5）：MCP、UE、设备、变量视图、日志。
 * 用 Kotlin UI DSL v2 绑定到 [LuaDebugSettings]。MCP 的开关和端口归 IDE 自带的 MCP 服务器，这里显示状态、给入口，
 * 并显示本项目的安装状态（各 Agent 配置里有没有 IDE 服务器的地址）和「安装/更新…」。
 */
class LuaDebugConfigurable : BoundConfigurable(LuaDebugBundle.message("settings.title")) {
    private val settings get() = LuaDebugSettings.getInstance()
    private val mcpState = JBLabel()
    private val installState = JBLabel()

    override fun createPanel(): DialogPanel = panel {
        row {
            link(LuaDebugBundle.message("settings.languageLink")) {
                SettingsJump.open(it.source as? java.awt.Component, SettingsJump.LANGUAGE_ID, "EmmyLua")
            }
        }
        group(LuaDebugBundle.message("settings.mcp")) {
            row { comment(LuaDebugBundle.message("settings.mcp.comment")) }
            row {
                cell(mcpState)
            }
            row {
                button(LuaDebugBundle.message("settings.mcp.open")) {
                    LuaMcpService.getInstance()?.openServerSettings(ProjectUtil.getActiveProject())
                    refreshMcpState()
                }.enabled(LuaMcpService.getInstance()?.serverState()?.available == true)
            }
            row(LuaDebugBundle.message("settings.mcp.installState")) {
                cell(installState)
                button(LuaDebugBundle.message("settings.mcp.install")) {
                    val project = ProjectUtil.getActiveProject() ?: return@button
                    LuaMcpInstallDialog.show(project)
                    refreshMcpState()
                    refreshInstallState()
                }.enabled(LuaMcpService.getInstance()?.serverState()?.available == true)
            }
            row {
                checkBox(LuaDebugBundle.message("settings.mcp.allowLua"))
                    .bindSelected(settings::allowAgentLuaEval)
                    .comment(LuaDebugBundle.message("settings.mcp.allowLua.comment"))
            }
        }
        group(LuaDebugBundle.message("settings.errors")) {
            row {
                checkBox(LuaDebugBundle.message("settings.errors.caught"))
                    .bindSelected(settings::collectCaughtLuaErrors)
                    .comment(LuaDebugBundle.message("settings.errors.caught.comment"))
            }
        }
        group(LuaDebugBundle.message("settings.ue")) {
            row {
                checkBox(LuaDebugBundle.message("settings.ue.reloadOnSave"))
                    .bindSelected(settings::reloadToUnrealOnSave)
                    .comment(LuaDebugBundle.message("settings.ue.reloadOnSave.comment"))
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
            row(LuaDebugBundle.message("settings.device.reference")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                        .withTitle(LuaDebugBundle.message("settings.device.reference.choose")),
                ).align(AlignX.FILL).bindText(settings::referenceSo)
                    .comment(LuaDebugBundle.message("settings.device.reference.comment"))
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
        onApply {
            // 变量视图设置推给正在调试的会话（variable-view.md 15.3）
            com.fixcode.intellij.lua.debug.xdebugger.LuaVariableViewSync.applyToAllSessions()
        }
        refreshMcpState()
        refreshInstallState()
    }

    private fun refreshMcpState() {
        val state = LuaMcpService.getInstance()?.serverState()
        mcpState.icon = when {
            state == null || !state.available -> AllIcons.General.Warning
            state.running -> AllIcons.General.InspectionsOK
            else -> AllIcons.General.InspectionsPause
        }
        mcpState.text = "<html>" + LuaMcpStatusText.describe() + "</html>"
    }

    /** 「本项目安装状态：Claude Code ✓  Cursor ✗ …」，读文件放后台。 */
    private fun refreshInstallState() {
        val service = LuaMcpService.getInstance() ?: return
        if (!service.serverState().available) return
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
                        s.installed && !s.outdated -> "✓"
                        s.installed || s.legacy -> "⚠"
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

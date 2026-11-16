package com.fixcode.intellij.lua.debug.ui.session

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.fixcode.intellij.lua.debug.api.LuaDebugSessionModel
import com.fixcode.intellij.lua.debug.api.LuaMappingStatus
import com.fixcode.intellij.lua.debug.api.LuaMcpCallRecord
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.api.LuaSourceMappingEntry
import com.fixcode.intellij.lua.debug.api.LuaVmInfo
import com.fixcode.intellij.lua.debug.api.LuaVmState
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/** 工具栏 + 表格的通用布局。 */
private fun toolbarPanel(place: String, table: JTable, actions: List<AnAction>): JComponent {
    val panel = JPanel(BorderLayout())
    val toolbar = ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(actions), true)
    toolbar.targetComponent = table
    panel.add(toolbar.component, BorderLayout.NORTH)
    panel.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
    return panel
}

private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, run: () -> Unit) =
    object : AnAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) = run()
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

private fun <T> column(name: String, value: (T) -> String, renderer: TableCellRenderer? = null) =
    object : ColumnInfo<T, String>(name) {
        override fun valueOf(item: T): String = value(item)
        override fun getRenderer(item: T): TableCellRenderer? = renderer
    }

// ---------------- VM ----------------

/** 「VM」页：当前会话所有 Lua VM，双击切换当前 VM（栈、变量、单步都作用于它）。 */
internal class LuaVmPanel(private val model: LuaDebugSessionModel, parent: Disposable) {
    private val tableModel = ListTableModel<LuaVmInfo>(
        column<LuaVmInfo>(LuaDebugBundle.message("tab.vm.name"), { it.displayName }, VmNameRenderer()),
        column<LuaVmInfo>(LuaDebugBundle.message("tab.vm.state"), { stateText(it.state) }),
        column<LuaVmInfo>(LuaDebugBundle.message("tab.vm.version"), { it.luaVersion.orEmpty() }),
        column<LuaVmInfo>(LuaDebugBundle.message("tab.vm.epoch"), { it.sourceEpoch.toString() }),
        column<LuaVmInfo>("ID", { it.vmId }),
    )
    private val table = TableView(tableModel).apply {
        emptyText.text = LuaDebugBundle.message("tab.vm.empty")
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectCurrent()
            }
        })
    }

    val component: JComponent = toolbarPanel("EmmyLua.VmTab", table, listOf(
        action(LuaDebugBundle.message("tab.vm.switch"), AllIcons.Actions.Checked,
            { table.selectedObject?.let { it.state != LuaVmState.CLOSED && it.vmId != model.currentVmId() } == true }) { selectCurrent() },
    ))

    init {
        refresh()
        model.addListener(object : LuaDebugSessionModel.Listener {
            override fun vmsChanged() = ApplicationManager.getApplication().invokeLater { refresh() }
        }, parent)
    }

    private fun refresh() {
        val selected = table.selectedObject?.vmId
        tableModel.items = model.vms().sortedWith(compareBy<LuaVmInfo> { it.state == LuaVmState.CLOSED }.thenBy { it.displayName })
        tableModel.items.indexOfFirst { it.vmId == selected }.takeIf { it >= 0 }
            ?.let { table.selectionModel.setSelectionInterval(it, it) }
    }

    private fun selectCurrent() {
        val vm = table.selectedObject ?: return
        if (vm.state == LuaVmState.CLOSED) return
        model.selectVm(vm.vmId)
        table.repaint()
    }

    private fun stateText(state: LuaVmState) = when (state) {
        LuaVmState.RUNNING -> LuaDebugBundle.message("tab.vm.running")
        LuaVmState.PAUSED -> LuaDebugBundle.message("tab.vm.paused")
        LuaVmState.CLOSED -> LuaDebugBundle.message("tab.vm.closed")
    }

    /** 当前 VM 加粗并带箭头，已关闭的置灰。 */
    private inner class VmNameRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val vm = tableModel.getItem(table.convertRowIndexToModel(row))
            val current = vm.vmId == model.currentVmId()
            icon = when {
                current -> AllIcons.Debugger.ThreadCurrent
                vm.state == LuaVmState.PAUSED -> AllIcons.Debugger.ThreadSuspended
                vm.state == LuaVmState.CLOSED -> AllIcons.Debugger.ThreadFrozen
                else -> AllIcons.Debugger.ThreadRunning
            }
            val attrs = when {
                vm.state == LuaVmState.CLOSED -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                current -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            append(vm.displayName, attrs)
        }
    }
}

// ---------------- 映射 ----------------

/** 「映射」页：本会话遇到的 chunkName → 本地文件，未映射 / 多候选 / hash 不一致的排在前面，可以手动指定。 */
internal class LuaMappingPanel(
    private val project: Project,
    private val model: LuaDebugSessionModel,
    parent: Disposable,
) {
    private val tableModel = ListTableModel<LuaSourceMappingEntry>(
        column<LuaSourceMappingEntry>(LuaDebugBundle.message("tab.mapping.status"), { statusText(it.status) }, StatusRenderer()),
        column<LuaSourceMappingEntry>(LuaDebugBundle.message("tab.mapping.chunk"), { it.chunkName }),
        column<LuaSourceMappingEntry>(LuaDebugBundle.message("tab.mapping.local"), { entry ->
            entry.localPath ?: entry.candidates.takeIf { it.isNotEmpty() }
                ?.let { LuaDebugBundle.message("tab.mapping.candidates", it.size) }.orEmpty()
        }),
    )
    private val table = TableView(tableModel).apply {
        emptyText.text = LuaDebugBundle.message("tab.mapping.empty")
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }
        })
    }

    val component: JComponent = toolbarPanel("EmmyLua.MappingTab", table, listOf(
        action(LuaDebugBundle.message("tab.mapping.choose"), AllIcons.Actions.MenuOpen, { table.selectedObject != null }) { chooseLocal() },
        action(LuaDebugBundle.message("tab.mapping.open"), AllIcons.Actions.EditSource,
            { table.selectedObject?.localPath != null }) { openSelected() },
    ))

    init {
        refresh()
        model.addListener(object : LuaDebugSessionModel.Listener {
            override fun mappingsChanged() = ApplicationManager.getApplication().invokeLater { refresh() }
        }, parent)
    }

    private fun refresh() {
        tableModel.items = model.sourceMappings().sortedWith(
            compareBy<LuaSourceMappingEntry> { it.status == LuaMappingStatus.MAPPED }.thenBy { it.chunkName }
        )
    }

    private fun openSelected() {
        val path = table.selectedObject?.localPath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    /** 多候选时直接从候选里选；否则打开文件选择框。 */
    private fun chooseLocal() {
        val entry = table.selectedObject ?: return
        if (entry.candidates.size > 1) {
            com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createPopupChooserBuilder(entry.candidates)
                .setTitle(LuaDebugBundle.message("tab.mapping.choose"))
                .setItemChosenCallback { model.overrideMapping(entry.chunkName, it) }
                .createPopup()
                .showInCenterOf(table)
            return
        }
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val file = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileDescriptor("lua")
                .withTitle(LuaDebugBundle.message("tab.mapping.choose")),
            project, base,
        ) ?: return
        model.overrideMapping(entry.chunkName, file.path)
    }

    private fun statusText(status: LuaMappingStatus) = LuaDebugBundle.message("tab.mapping.status.${status.name.lowercase()}")

    private inner class StatusRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val entry = tableModel.getItem(table.convertRowIndexToModel(row))
            icon = when (entry.status) {
                LuaMappingStatus.MAPPED -> AllIcons.General.InspectionsOK
                LuaMappingStatus.UNMAPPED, LuaMappingStatus.STRIPPED -> AllIcons.General.Error
                LuaMappingStatus.AMBIGUOUS, LuaMappingStatus.HASH_MISMATCH -> AllIcons.General.Warning
            }
            append(statusText(entry.status))
        }
    }
}

// ---------------- Agent ----------------

/**
 * 「Agent」页：MCP 调用实时记录，带"撤销 Agent 控制""清除 Agent 断点"（设计文档 6.2 / 10.4）。
 * 记录是全局的（MCP 端点只有一个），这里不按会话过滤，免得 Agent 起新会话前的调用看不到。
 */
internal class LuaAgentPanel(
    private val project: Project,
    private val mcp: LuaMcpService,
    parent: Disposable,
) {
    private val timeFormat = SimpleDateFormat("HH:mm:ss")
    private val tableModel = ListTableModel<LuaMcpCallRecord>(
        column<LuaMcpCallRecord>(LuaDebugBundle.message("tab.agent.time"), { timeFormat.format(Date(it.timestampMillis)) }, ResultRenderer()),
        column<LuaMcpCallRecord>(LuaDebugBundle.message("tab.agent.client"), { it.clientName.orEmpty() }),
        column<LuaMcpCallRecord>(LuaDebugBundle.message("tab.agent.tool"), { it.tool }),
        column<LuaMcpCallRecord>(LuaDebugBundle.message("tab.agent.args"), { it.argsSummary }),
        column<LuaMcpCallRecord>(LuaDebugBundle.message("tab.agent.result"), { it.resultSummary }),
    )
    private val table = TableView(tableModel).apply {
        emptyText.text = LuaDebugBundle.message("tab.agent.empty")
    }

    val component: JComponent = toolbarPanel("EmmyLua.AgentTab", table, listOf(
        action(LuaDebugBundle.message("tab.agent.revoke"), AllIcons.Actions.Suspend) { mcp.revokeAgentControl() },
        action(LuaDebugBundle.message("tab.agent.clearBreakpoints"), AllIcons.Debugger.Db_set_breakpoint) {
            mcp.clearAgentBreakpoints(project)
        },
        action(LuaDebugBundle.message("tab.agent.refresh"), AllIcons.Actions.Refresh) { refresh() },
    ))

    init {
        refresh()
        mcp.addListener({ ApplicationManager.getApplication().invokeLater { refresh() } }, parent)
    }

    private fun refresh() {
        val atBottom = table.rowCount == 0 || table.visibleRect.maxY >= table.height - table.rowHeight
        // 新的放最上面，正在调试时一眼能看到最近一次调用
        tableModel.items = mcp.recentCalls().asReversed()
        if (atBottom && table.rowCount > 0) table.scrollRectToVisible(table.getCellRect(0, 0, true))
    }

    private inner class ResultRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val record = tableModel.getItem(table.convertRowIndexToModel(row))
            icon = if (record.success) AllIcons.General.InspectionsOK else AllIcons.General.Error
            append(value?.toString().orEmpty())
        }
    }
}

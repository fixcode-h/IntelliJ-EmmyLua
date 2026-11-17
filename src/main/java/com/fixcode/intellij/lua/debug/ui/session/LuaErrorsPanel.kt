package com.fixcode.intellij.lua.debug.ui.session

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.fixcode.intellij.lua.debug.api.LuaErrorRow
import com.fixcode.intellij.lua.debug.api.LuaErrorsModel
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.xdebugger.LuaProfileNames
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * 「Lua 错误」页：runtime 按位置去重计数的 Lua 错误（协议第 21 节），包括被 pcall 接住、每帧刷屏的那种。
 * 上面是表（消息、位置、次数、首次 / 最近时间、是否被接住），下面是选中那条的栈；双击跳到出错行。
 * 新错误靠推送马上出现；页签显示着时每 2 秒向 runtime 拉一次精确计数（推送是限流过的）。
 */
internal class LuaErrorsPanel(
    private val project: Project,
    private val model: LuaErrorsModel,
    parent: Disposable,
) {
    private val tableModel = ListTableModel<LuaErrorRow>(
        arrayOf(
            textColumn(LuaDebugBundle.message("tab.errors.message")) { firstLine(it.message) },
            textColumn(LuaDebugBundle.message("tab.errors.location")) { locationText(it) },
            countColumn(),
            textColumn(LuaDebugBundle.message("tab.errors.last"), comparator = compareBy { it.lastMs }) { time(it.lastMs) },
            textColumn(LuaDebugBundle.message("tab.errors.first"), comparator = compareBy { it.firstMs }) { time(it.firstMs) },
            textColumn(LuaDebugBundle.message("tab.errors.kind")) {
                LuaDebugBundle.message(if (it.uncaught) "tab.errors.kind.uncaught" else "tab.errors.kind.caught")
            },
        ),
        emptyList(), 3, SortOrder.DESCENDING,
    ).apply { isSortable = true }

    private val table = TableView(tableModel).apply {
        emptyText.text = LuaDebugBundle.message("tab.errors.empty")
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedObject?.let(::open)
            }
        })
        selectionModel.addListSelectionListener { showDetail(selectedObject) }
    }
    private val detail = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
        border = JBUI.Borders.empty(4, 6)
    }
    private val filter = SearchTextField(false).apply {
        textEditor.emptyText.text = LuaDebugBundle.message("tab.errors.filter")
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
    }
    private val status = JBLabel(LuaDebugBundle.message("tab.errors.hint")).apply { border = JBUI.Borders.empty(2, 6) }
    private var all: List<LuaErrorRow> = emptyList()
    private var onlyUncaught = false
    @Volatile
    private var busy = false
    private val refresher = Timer(2000) { if (!busy && component.isShowing && model.errorsSupported()) refresh() }

    val component: JComponent = JPanel(BorderLayout())

    init {
        val actions = DefaultActionGroup(
            action(LuaDebugBundle.message("tab.errors.refresh"), AllIcons.Actions.Refresh, { model.errorsSupported() && !busy }) { refresh() },
            action(LuaDebugBundle.message("tab.errors.open"), AllIcons.Actions.EditSource, { table.selectedObject?.localPath != null }) {
                table.selectedObject?.let(::open)
            },
            action(LuaDebugBundle.message("tab.errors.clear"), AllIcons.Actions.GC, { all.isNotEmpty() }) { clear() },
            object : ToggleAction(LuaDebugBundle.message("tab.errors.onlyUncaught"), null, AllIcons.General.Filter) {
                override fun isSelected(e: AnActionEvent) = onlyUncaught
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    onlyUncaught = state
                    applyFilter()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("EmmyLua.ErrorsTab", actions, true)
        toolbar.targetComponent = table
        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(filter, BorderLayout.CENTER)
        }
        val split = Splitter(true, 0.65f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(table)
            secondComponent = ScrollPaneFactory.createScrollPane(detail)
        }
        component.add(top, BorderLayout.NORTH)
        component.add(split, BorderLayout.CENTER)
        component.add(status, BorderLayout.SOUTH)

        model.addErrorsListener({ show(model.cachedErrors()) }, parent)
        refresher.start()
        Disposer.register(parent) { refresher.stop() }
        show(model.cachedErrors())
    }

    private fun refresh() {
        busy = true
        model.refreshErrors().whenComplete { rows, error ->
            ApplicationManager.getApplication().invokeLater {
                busy = false
                if (project.isDisposed) return@invokeLater
                if (error != null) status.text = LuaDebugBundle.message("tab.errors.failed", messageOf(error))
                else show(rows)
            }
        }
    }

    private fun clear() {
        model.clearErrors().whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (error != null) status.text = LuaDebugBundle.message("tab.errors.failed", messageOf(error))
                show(model.cachedErrors())
            }
        }
    }

    private fun show(rows: List<LuaErrorRow>) {
        all = rows
        applyFilter()
        val total = rows.sumOf { it.count }
        status.text = when {
            !model.errorsSupported() && rows.isEmpty() -> LuaDebugBundle.message("tab.errors.unsupported")
            rows.isEmpty() -> LuaDebugBundle.message("tab.errors.hint")
            else -> LuaDebugBundle.message("tab.errors.summary", rows.size, total)
        }
    }

    private fun applyFilter() {
        val text = filter.text.trim()
        val selectedId = table.selectedObject?.id
        tableModel.items = all.filter { row ->
            (!onlyUncaught || row.uncaught) &&
                (text.isEmpty() || row.message.contains(text, ignoreCase = true) || locationText(row).contains(text, ignoreCase = true))
        }
        // 刷新计数时保持选中，栈不要闪
        val index = tableModel.items.indexOfFirst { it.id == selectedId }
        if (index >= 0) {
            val view = table.convertRowIndexToView(index)
            if (view >= 0) table.selectionModel.setSelectionInterval(view, view)
        } else {
            showDetail(null)
        }
    }

    private fun showDetail(row: LuaErrorRow?) {
        if (row == null) {
            detail.text = ""
            return
        }
        val text = buildString {
            append(row.message).append('\n')
            append(LuaDebugBundle.message("tab.errors.detail.count", row.count, time(row.firstMs), time(row.lastMs))).append('\n')
            row.traceback?.takeIf { it.isNotBlank() }?.let { append('\n').append(LuaDebugBundle.message("tab.errors.detail.stack")).append('\n').append(it) }
        }
        if (detail.text != text) {
            detail.text = text
            detail.caretPosition = 0
        }
    }

    private fun open(row: LuaErrorRow) {
        val path = row.localPath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        OpenFileDescriptor(project, file, maxOf((row.line ?: 1) - 1, 0), 0).navigate(true)
    }

    private fun locationText(row: LuaErrorRow): String {
        val name = row.localPath?.let { File(it).name } ?: row.chunkName?.let { LuaProfileNames.chunkDisplay(it) } ?: return ""
        return if (row.line != null && row.line > 0) "$name:${row.line}" else name
    }

    private fun firstLine(text: String) = text.lineSequence().firstOrNull().orEmpty()

    private fun time(ms: Long): String = if (ms <= 0) "" else TIME_FORMAT.get().format(Date(ms))

    private fun messageOf(error: Throwable): String {
        var e = error
        while ((e is CompletionException || e is ExecutionException) && e.cause != null) e = e.cause!!
        return e.message ?: e.javaClass.simpleName
    }

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean, run: () -> Unit) =
        object : AnAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun textColumn(name: String, comparator: Comparator<LuaErrorRow>? = null, value: (LuaErrorRow) -> String) =
        object : ColumnInfo<LuaErrorRow, String>(name) {
            override fun valueOf(item: LuaErrorRow): String = value(item)
            override fun getComparator(): Comparator<LuaErrorRow> = comparator ?: compareBy(String.CASE_INSENSITIVE_ORDER, value)
        }

    /** 次数列：右对齐、按数值排序，列宽只够放表头和数字。 */
    private fun countColumn() = object : ColumnInfo<LuaErrorRow, String>(LuaDebugBundle.message("tab.errors.count")) {
        override fun valueOf(item: LuaErrorRow): String = item.count.toString()
        override fun getComparator(): Comparator<LuaErrorRow> = compareBy { it.count }
        override fun getWidth(table: JTable): Int = JBUI.scale(70)
        override fun getRenderer(item: LuaErrorRow?): TableCellRenderer = RIGHT
    }

    private companion object {
        val TIME_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss") }
        val RIGHT = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }
    }
}

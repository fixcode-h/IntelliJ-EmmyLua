package com.fixcode.intellij.lua.debug.ui.session

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.fixcode.intellij.lua.debug.api.LuaProfileRow
import com.fixcode.intellij.lua.debug.api.LuaProfileSnapshot
import com.fixcode.intellij.lua.debug.api.LuaProfilerModel
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.mcp.LuaMcpEnablePrompt
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SortOrder
import javax.swing.Timer

/**
 * 「Lua 采样」页：开始 / 停止采样，结果是可排序的函数表（双击跳到函数定义），可以导出折叠栈。
 * 采样中每 2 秒刷新一次表格，看着热点变化；停止后保留最后一次结果直到下次开始。
 */
internal class LuaProfilerPanel(
    private val project: Project,
    private val model: LuaProfilerModel,
    parent: Disposable,
) {
    private val tableModel = ListTableModel<LuaProfileRow>(
        arrayOf(
            textColumn(LuaDebugBundle.message("tab.profiler.function")) { it.function },
            textColumn(LuaDebugBundle.message("tab.profiler.location")) { locationText(it) },
            numberColumn(LuaDebugBundle.message("tab.profiler.self"), percent = true) { it.selfPercent },
            numberColumn(LuaDebugBundle.message("tab.profiler.total"), percent = true) { it.totalPercent },
            numberColumn(LuaDebugBundle.message("tab.profiler.selfSamples")) { it.selfSamples.toDouble() },
            numberColumn(LuaDebugBundle.message("tab.profiler.totalSamples")) { it.totalSamples.toDouble() },
        ),
        emptyList(), 2, SortOrder.DESCENDING,
    ).apply { isSortable = true }

    private val table = TableView(tableModel).apply {
        emptyText.text = LuaDebugBundle.message("tab.profiler.empty")
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }
        })
    }
    private val status = JBLabel(LuaDebugBundle.message("tab.profiler.hint")).apply { border = JBUI.Borders.empty(2, 6) }

    @Volatile
    private var running = false
    @Volatile
    private var busy = false
    private var last: LuaProfileSnapshot? = null
    private val refresher = Timer(2000) { if (running && !busy) poll() }

    val component: JComponent = JPanel(BorderLayout()).apply {
        val actions = DefaultActionGroup(
            action(LuaDebugBundle.message("tab.profiler.start"), LuaDebugBundle.message("tab.profiler.start.tip"), AllIcons.Actions.Execute,
                { !running && !busy && model.profilerSupported() }) { start() },
            action(LuaDebugBundle.message("tab.profiler.stop"), null, AllIcons.Actions.Suspend, { running && !busy }) { stop() },
            action(LuaDebugBundle.message("tab.profiler.open"), null, AllIcons.Actions.EditSource,
                { table.selectedObject?.localPath != null }) { openSelected() },
            action(LuaDebugBundle.message("tab.profiler.export"), LuaDebugBundle.message("tab.profiler.export.tip"), AllIcons.ToolbarDecorator.Export,
                { last?.folded?.isNotEmpty() == true }) { export() },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("EmmyLua.ProfilerTab", actions, true)
        toolbar.targetComponent = table
        add(toolbar.component, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    init {
        Disposer.register(parent) {
            refresher.stop()
            // 调试会话结束时 runtime 会随断线停止采样，这里不用再发 stop
        }
    }

    private fun start() {
        busy = true
        status.text = LuaDebugBundle.message("tab.profiler.starting")
        handle(model.startProfiling()) {
            running = true
            last = null
            tableModel.items = emptyList()
            status.text = LuaDebugBundle.message("tab.profiler.running")
            refresher.start()
        }
    }

    private fun stop() {
        busy = true
        refresher.stop()
        handle(model.stopProfiling()) { show(it) }
    }

    private fun poll() {
        busy = true
        handle(model.profileSnapshot()) { show(it) }
    }

    private fun show(snapshot: LuaProfileSnapshot) {
        last = snapshot
        running = snapshot.running
        if (!running) refresher.stop()
        val selected = table.selectedObject?.let { it.chunkName to it.line }
        tableModel.items = snapshot.rows
        tableModel.items.indexOfFirst { (it.chunkName to it.line) == selected }.takeIf { it >= 0 }
            ?.let { table.convertRowIndexToView(it) }?.takeIf { it >= 0 }
            ?.let { table.selectionModel.setSelectionInterval(it, it) }
        val seconds = String.format(Locale.ROOT, "%.1f", snapshot.durationMs / 1000.0)
        val key = if (running) "tab.profiler.status.running" else "tab.profiler.status.done"
        var text = LuaDebugBundle.message(key, snapshot.samples, seconds, snapshot.rows.size)
        if (snapshot.stacksTruncated) text += LuaDebugBundle.message("tab.profiler.status.truncated", snapshot.totalStacks)
        status.text = text
    }

    /** 回调切回 EDT；失败时把原因写在状态栏（runtime 的说明是中文、写了怎么办）。 */
    private fun <T> handle(future: CompletableFuture<T>, onSuccess: (T) -> Unit) {
        future.whenComplete { value, error ->
            ApplicationManager.getApplication().invokeLater {
                busy = false
                if (project.isDisposed) return@invokeLater
                if (error != null) {
                    status.text = LuaDebugBundle.message("tab.profiler.failed", messageOf(error))
                    if (!model.profilerSupported()) running = false
                } else {
                    onSuccess(value)
                }
            }
        }
    }

    private fun openSelected() {
        val row = table.selectedObject ?: return
        val path = row.localPath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        // linedefined 从 1 开始；主 chunk 是 0，跳到文件开头
        OpenFileDescriptor(project, file, maxOf(row.line - 1, 0), 0).navigate(true)
    }

    private fun export() {
        val snapshot = last ?: return
        val descriptor = FileSaverDescriptor(
            LuaDebugBundle.message("tab.profiler.export"), LuaDebugBundle.message("tab.profiler.export.tip"), "folded"
        )
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(base, "lua-profile.folded") ?: return
        val file: File = wrapper.file
        try {
            file.writeText(snapshot.folded, Charsets.UTF_8)
        } catch (error: Exception) {
            status.text = LuaDebugBundle.message("tab.profiler.failed", error.message ?: error.toString())
            return
        }
        NotificationGroupManager.getInstance().getNotificationGroup(LuaMcpEnablePrompt.GROUP_ID)
            .createNotification(LuaDebugBundle.message("tab.profiler.exported", file.path), NotificationType.INFORMATION)
            .notify(project)
    }

    private fun locationText(row: LuaProfileRow): String {
        if (row.chunkName == "=[C]") return "[C]"
        if (row.chunkName.isEmpty()) return ""
        val name = row.localPath?.let { File(it).name } ?: row.chunkName.removePrefix("@").removePrefix("=")
        return "$name:${maxOf(row.line, 1)}"
    }

    private fun messageOf(error: Throwable): String {
        var cause: Throwable = error
        while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.toString()
    }

    private fun action(text: String, description: String?, icon: javax.swing.Icon, enabled: () -> Boolean, run: () -> Unit) =
        object : AnAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun textColumn(name: String, value: (LuaProfileRow) -> String) = object : ColumnInfo<LuaProfileRow, String>(name) {
        override fun valueOf(item: LuaProfileRow): String = value(item)
        override fun getComparator(): Comparator<LuaProfileRow> = compareBy(String.CASE_INSENSITIVE_ORDER, value)
    }

    /** 数字列：百分比保留两位小数、样本数显示整数，按数值排序。 */
    private fun numberColumn(name: String, percent: Boolean = false, value: (LuaProfileRow) -> Double) =
        object : ColumnInfo<LuaProfileRow, String>(name) {
        override fun valueOf(item: LuaProfileRow): String {
            val v = value(item)
            return if (percent) String.format(Locale.ROOT, "%.2f", v) else v.toLong().toString()
        }
        override fun getComparator(): Comparator<LuaProfileRow> = compareBy(value)
    }
}

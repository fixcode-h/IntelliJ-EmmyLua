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
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.fixcode.intellij.lua.debug.api.LuaProfileRow
import com.fixcode.intellij.lua.debug.api.LuaProfileSnapshot
import com.fixcode.intellij.lua.debug.api.LuaProfilerModel
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.mcp.LuaMcpInstallPrompt
import com.fixcode.intellij.lua.debug.xdebugger.LuaProfileNames
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
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.SortOrder
import javax.swing.Timer

/**
 * 「Lua 采样」页：开始 / 停止采样。结果有三种看法：可排序的函数表（可按函数名 / 文件过滤、隐藏 C 函数）、
 * 调用树、火焰图（都由折叠栈建，见 [LuaCallTree]），双击跳到函数定义；可以导出折叠栈。
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
    private val filter = SearchTextField(false).apply {
        textEditor.emptyText.text = LuaDebugBundle.message("tab.profiler.filter")
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
    }
    private var hideC = false
    private var callRoot: LuaCallNode? = null
    private var rowsByLabel: Map<String, LuaProfileRow> = emptyMap()

    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode())).apply {
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = LuaDebugBundle.message("tab.profiler.empty")
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val node = (value as? DefaultMutableTreeNode)?.userObject as? LuaCallNode ?: return
                val all = callRoot?.total?.takeIf { it > 0 } ?: return
                append(String.format(Locale.ROOT, "%5.1f%%  ", 100.0 * node.total / all), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(node.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (node.location.isNotEmpty()) append("  ${node.location}", SimpleTextAttributes.GRAY_ATTRIBUTES)
                if (node.self > 0) append("  " + LuaDebugBundle.message("tab.profiler.tree.self", String.format(Locale.ROOT, "%.1f", 100.0 * node.self / all)), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedTreeNode()?.let(::navigate)
            }
        })
    }
    private val flame = LuaFlameGraph(::navigate)
    private val views = JBTabbedPane().apply {
        addTab(LuaDebugBundle.message("tab.profiler.view.table"), ScrollPaneFactory.createScrollPane(table))
        addTab(LuaDebugBundle.message("tab.profiler.view.tree"), ScrollPaneFactory.createScrollPane(tree))
        addTab(LuaDebugBundle.message("tab.profiler.view.flame"), ScrollPaneFactory.createScrollPane(flame))
        addChangeListener { filter.isVisible = selectedIndex == VIEW_TABLE }
    }
    private var allRows: List<LuaProfileRow> = emptyList()
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
            action(LuaDebugBundle.message("tab.profiler.open"), null, AllIcons.Actions.EditSource, ::canOpenSelected) { openSelected() },
            action(LuaDebugBundle.message("tab.profiler.export"), LuaDebugBundle.message("tab.profiler.export.tip"), AllIcons.ToolbarDecorator.Export,
                { last?.folded?.isNotEmpty() == true }) { export() },
            object : ToggleAction(LuaDebugBundle.message("tab.profiler.hideC"), LuaDebugBundle.message("tab.profiler.hideC.tip"), AllIcons.General.Filter) {
                override fun isSelected(e: AnActionEvent) = hideC
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    hideC = state
                    applyFilter()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("EmmyLua.ProfilerTab", actions, true)
        toolbar.targetComponent = table
        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(filter, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(views, BorderLayout.CENTER)
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
            allRows = emptyList()
            tableModel.items = emptyList()
            showCalls(null)
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
        allRows = snapshot.rows
        applyFilter()
        rowsByLabel = snapshot.rows.filter { it.chunkName != C_SOURCE && it.chunkName.isNotEmpty() }
            .associateBy { LuaProfileNames.label(it.function, it.chunkName, it.line) }
        showCalls(LuaCallTree.parse(snapshot.folded))
        val seconds = String.format(Locale.ROOT, "%.1f", snapshot.durationMs / 1000.0)
        val key = if (running) "tab.profiler.status.running" else "tab.profiler.status.done"
        var text = LuaDebugBundle.message(key, snapshot.samples, seconds, snapshot.rows.size)
        if (snapshot.stacksTruncated) text += LuaDebugBundle.message("tab.profiler.status.truncated", snapshot.totalStacks)
        status.text = text
    }

    /** 按过滤框和「隐藏 C 函数」重填表格，保留选中行。 */
    private fun applyFilter() {
        val selected = table.selectedObject?.let { Triple(it.chunkName, it.line, it.function) }
        val query = filter.text.trim()
        tableModel.items = allRows.filter { row ->
            (!hideC || row.chunkName != C_SOURCE) &&
                (query.isEmpty() || row.function.contains(query, ignoreCase = true) || locationText(row).contains(query, ignoreCase = true) ||
                    row.localPath?.contains(query, ignoreCase = true) == true)
        }
        tableModel.items.indexOfFirst { Triple(it.chunkName, it.line, it.function) == selected }.takeIf { it >= 0 }
            ?.let { table.convertRowIndexToView(it) }?.takeIf { it >= 0 }
            ?.let { table.selectionModel.setSelectionInterval(it, it) }
    }

    /** 换调用树和火焰图的数据；采样中刷新时保留调用树里展开过的节点，第一次显示时展开最热的那条路径。 */
    private fun showCalls(root: LuaCallNode?) {
        val expanded = tree.getExpandedDescendants(TreePath(tree.model.root))?.toList().orEmpty()
            .map { path -> path.path.drop(1).map { ((it as DefaultMutableTreeNode).userObject as LuaCallNode).label } }
        val selected = selectedTreeNode()?.path
        callRoot = root
        flame.setRoot(root)
        val treeRoot = DefaultMutableTreeNode(root)
        root?.children?.forEach { treeRoot.add(treeNode(it)) }
        (tree.model as DefaultTreeModel).setRoot(treeRoot)
        if (root == null) return
        if (expanded.isEmpty()) {
            var node: DefaultMutableTreeNode = treeRoot
            while (node.childCount > 0) {
                node = node.getChildAt(0) as DefaultMutableTreeNode
                if (node.childCount > 0) tree.expandPath(TreePath(node.path))
            }
        } else {
            expanded.forEach { labels -> treePath(treeRoot, labels)?.let(tree::expandPath) }
        }
        selected?.let { treePath(treeRoot, it) }?.let { tree.selectionPath = it }
    }

    private fun treeNode(node: LuaCallNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node).also { n -> node.children.forEach { n.add(treeNode(it)) } }

    private fun treePath(root: DefaultMutableTreeNode, labels: List<String>): TreePath? {
        var node = root
        for (label in labels) {
            node = node.children().asSequence().map { it as DefaultMutableTreeNode }
                .firstOrNull { (it.userObject as LuaCallNode).label == label } ?: return null
        }
        return TreePath(node.path)
    }

    private fun selectedTreeNode(): LuaCallNode? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? LuaCallNode

    private fun canOpenSelected(): Boolean = when (views.selectedIndex) {
        VIEW_TABLE -> table.selectedObject?.localPath != null
        VIEW_TREE -> selectedTreeNode()?.let { rowsByLabel[it.label]?.localPath } != null
        else -> false
    }

    /** 调用树 / 火焰图的节点跳到源码：按帧标签找回函数表那一行（它带着本地路径和定义行）。 */
    private fun navigate(node: LuaCallNode) {
        rowsByLabel[node.label]?.let(::open)
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
        when (views.selectedIndex) {
            VIEW_TABLE -> table.selectedObject?.let(::open)
            VIEW_TREE -> selectedTreeNode()?.let(::navigate)
        }
    }

    private fun open(row: LuaProfileRow) {
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
        NotificationGroupManager.getInstance().getNotificationGroup(LuaMcpInstallPrompt.GROUP_ID)
            .createNotification(LuaDebugBundle.message("tab.profiler.exported", file.path), NotificationType.INFORMATION)
            .notify(project)
    }

    private fun locationText(row: LuaProfileRow): String {
        if (row.chunkName == C_SOURCE) return "[C]"
        if (row.chunkName.isEmpty()) return ""
        // 字符串 load 出来的 chunk，chunkName 是代码本身：显示成 [string "第一行..."]
        val name = row.localPath?.let { File(it).name } ?: LuaProfileNames.chunkDisplay(row.chunkName)
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

    /** 数字列：百分比保留两位小数、样本数显示整数，右对齐，按数值排序；列宽只够放表头和数字，剩下的宽度给函数和文件列。 */
    private fun numberColumn(name: String, percent: Boolean = false, value: (LuaProfileRow) -> Double) =
        object : ColumnInfo<LuaProfileRow, String>(name) {
        override fun valueOf(item: LuaProfileRow): String {
            val v = value(item)
            return if (percent) String.format(Locale.ROOT, "%.2f", v) else v.toLong().toString()
        }
        override fun getComparator(): Comparator<LuaProfileRow> = compareBy(value)
        override fun getRenderer(item: LuaProfileRow?): TableCellRenderer = RIGHT_ALIGNED
        override fun getWidth(table: JTable): Int {
            val header = table.tableHeader?.let { it.getFontMetrics(it.font).stringWidth(name) } ?: 0
            val digits = table.getFontMetrics(table.font).stringWidth(if (percent) "100.00" else "0000000")
            return maxOf(header, digits) + JBUI.scale(24)
        }
    }

    private companion object {
        const val C_SOURCE = "=[C]"
        const val VIEW_TABLE = 0
        const val VIEW_TREE = 1
        val RIGHT_ALIGNED = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }
    }
}

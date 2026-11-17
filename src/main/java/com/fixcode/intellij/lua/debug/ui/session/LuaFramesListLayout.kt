package com.fixcode.intellij.lua.debug.ui.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JViewport
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * 调用栈列表里函数名靠左、`相对路径:行号` 靠右（用户要求，像 VS 的调用堆栈那样两列分开好扫）。
 *
 * 帧的显示文字经 XFramesAsyncPresentationHandler 缓存后只剩「文字 + 样式」，帧自己没法设对齐，
 * 所以在本会话的帧列表上包一层渲染器：平台渲染完后，把最后一段（帧追加的位置，两个空格开头）
 * 右对齐到列表右边，放不下时从左边省略路径。列表宽度固定跟视口走，避免右对齐把首选宽度撑大后
 * 出现横向滚动条、缩窄面板时位置跟不回来；为此也关掉悬停展开（位置已经省略到放得下）。
 */
internal object LuaFramesListLayout {

    private const val MARK = "EmmyLua.FramesLayout"

    /** 在会话界面里找帧列表装上；没找到返回 false（界面还没建好，下次暂停再试）。 */
    fun install(root: JComponent, parent: Disposable): Boolean {
        val lists = UIUtil.findComponentsOfType(root, JList::class.java)
            .filter { it.javaClass.name.endsWith("XDebuggerFramesList") }
        if (lists.isEmpty()) return false
        lists.forEach { install(it, parent) }
        return true
    }

    private fun install(list: JList<*>, parent: Disposable) {
        if (list.getClientProperty(MARK) != null) return
        list.putClientProperty(MARK, true)
        @Suppress("UNCHECKED_CAST")
        val raw = list as JList<Any?>
        val original = raw.cellRenderer ?: return
        raw.cellRenderer = Renderer(original)
        (list as? JBList<*>)?.setExpandableItemsEnabled(false)

        val viewport = list.parent as? JViewport
        val resize = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fitToViewport(list, viewport)
        }
        viewport?.addComponentListener(resize)
        fitToViewport(list, viewport)

        Disposer.register(parent) {
            viewport?.removeComponentListener(resize)
            if (raw.cellRenderer is Renderer) raw.cellRenderer = original
            list.fixedCellWidth = -1
            (list as? JBList<*>)?.setExpandableItemsEnabled(true)
            list.putClientProperty(MARK, null)
        }
    }

    private fun fitToViewport(list: JList<*>, viewport: JViewport?) {
        val width = viewport?.extentSize?.width ?: return
        if (width > 0 && list.fixedCellWidth != width) list.fixedCellWidth = width
    }

    private class Renderer(private val delegate: ListCellRenderer<Any?>) : ListCellRenderer<Any?> {
        override fun getListCellRendererComponent(
            list: JList<out Any?>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            val result = delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            runCatching { alignLocation(list, result) }
            return result
        }
    }

    internal fun alignLocation(list: JList<*>, result: Component) {
        val text = (result as? SimpleColoredComponent)
            ?: (result as? JComponent)?.let { UIUtil.findComponentOfType(it, SimpleColoredComponent::class.java) }
            ?: return
        val cellWidth = list.width - list.insets.left - list.insets.right
        if (cellWidth <= 0) return

        // 最后一段才是位置；搜索高亮把它拆开时不动，照原样显示
        val it = text.iterator()
        var location: SimpleColoredComponent.ColoredIterator? = null
        while (it.hasNext()) { it.next(); location = it }
        val last = location ?: return
        if (text.fragmentCount < 2) return
        // 只认「两个空格开头」，不比样式：2025.3 起帧经 RPC 序列化后在前端重建（FrontendXStackFrame），
        // 灰色变成了新的 SimpleTextAttributes，和 GRAY_ATTRIBUTES 不相等，比样式会整段跳过
        val fragment = last.fragment
        if (!fragment.startsWith("  ")) return

        // 渲染组件在单元格里的右边界：减去外层面板（新 UI 的选中框）和自己的边距
        var right = cellWidth
        var c: Component? = text
        while (c != null && c !== result.parent) {
            if (c is JComponent) c.insets.let { right -= if (c === text) it.right else it.left + it.right }
            c = c.parent
        }
        right -= text.ipad.right

        val metrics = text.getFontMetrics(text.font)
        val width = metrics.stringWidth(fragment)
        val natural = text.preferredSize.width - text.insets.right - text.ipad.right
        val nameEnd = natural - width
        val fitted = fit(fragment, right - nameEnd) { metrics.stringWidth(it) } ?: return
        if (fitted != fragment) last.fragment = fitted
        text.appendTextPadding(right, SwingConstants.RIGHT)
    }

    /**
     * 把 `  路径:行号` 缩到 [available] 像素内：从左边去掉目录，前面补「…」；连 `…文件名:行号` 都放不下返回 null
     * （照原样跟在函数名后面）。
     */
    fun fit(location: String, available: Int, widthOf: (String) -> Int): String? {
        if (widthOf(location) <= available) return location
        val body = location.trimStart()
        val prefix = location.substring(0, location.length - body.length) + "…"
        val minStart = body.lastIndexOf('/').coerceAtLeast(body.lastIndexOf('\\')) + 1
        for (start in 1..minStart) {
            val candidate = prefix + body.substring(start)
            if (widthOf(candidate) <= available) return candidate
        }
        return null
    }
}

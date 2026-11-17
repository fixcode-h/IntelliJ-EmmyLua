package com.fixcode.intellij.lua.debug.ui.session

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.ToolTipManager

/**
 * 火焰图（冰柱图：根在最上面，往下是被调用的函数），宽度 = 总样本数。
 * 单击放大到这个函数（点上面的祖先或空白处缩回），双击跳到源码，悬停显示样本数和占比。
 */
internal class LuaFlameGraph(private val navigate: (LuaCallNode) -> Unit) : JComponent(), Scrollable {
    private var root: LuaCallNode? = null
    private var focus: LuaCallNode? = null
    private var hovered: LuaCallNode? = null
    private val painted = ArrayList<Pair<Rectangle, LuaCallNode>>()

    init {
        isOpaque = true
        ToolTipManager.sharedInstance().registerComponent(this)
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val node = nodeAt(e.x, e.y)
                when {
                    e.clickCount == 2 && node != null -> navigate(node)
                    e.clickCount == 1 -> {
                        focus = node ?: root
                        repaint()
                    }
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                val node = nodeAt(e.x, e.y)
                if (node !== hovered) {
                    hovered = node
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = null
                repaint()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    /** 换数据；采样中刷新时按标签路径找回原来放大的节点。 */
    fun setRoot(newRoot: LuaCallNode?) {
        val focusPath = focus?.path
        root = newRoot
        focus = focusPath?.let { newRoot?.find(it) } ?: newRoot
        hovered = null
        revalidate()
        repaint()
    }

    private val rowHeight get() = getFontMetrics(font).height + JBUI.scale(4)

    override fun getPreferredSize(): Dimension {
        val r = root ?: return Dimension(0, 0)
        val f = focus ?: r
        return Dimension(JBUI.scale(200), (f.depth + f.height) * rowHeight)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = rowHeight
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = parent?.let { it.height > preferredSize.height } ?: false

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = UIUtil.getPanelBackground()
        g2.fillRect(0, 0, width, height)
        painted.clear()
        val r = root
        if (r == null || r.total == 0L) {
            g2.color = UIUtil.getInactiveTextColor()
            val text = LuaDebugBundle.message("tab.profiler.flame.empty")
            g2.drawString(text, JBUI.scale(8), getFontMetrics(font).ascent + JBUI.scale(8))
            return
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val f = focus ?: r
        // 放大时祖先占满整行、颜色变淡，点它们缩回
        var ancestor = f.parent
        while (ancestor != null) {
            drawFrame(g2, ancestor, 0.0, width.toDouble(), dim = true)
            ancestor = ancestor.parent
        }
        drawTree(g2, f, 0.0, width.toDouble())
    }

    private fun drawTree(g2: Graphics2D, node: LuaCallNode, x: Double, w: Double) {
        if (w < 1.0) return
        drawFrame(g2, node, x, w, dim = false)
        if (node.total == 0L) return
        var cx = x
        for (child in node.children) {
            val cw = w * child.total / node.total
            drawTree(g2, child, cx, cw)
            cx += cw
        }
    }

    private fun drawFrame(g2: Graphics2D, node: LuaCallNode, x: Double, w: Double, dim: Boolean) {
        val h = rowHeight
        val rect = Rectangle(x.toInt(), node.depth * h, maxOf(1, (x + w).toInt() - x.toInt() - 1), h - 1)
        painted.add(rect to node)
        var color = colorOf(node)
        if (dim) color = blend(color, UIUtil.getPanelBackground(), 0.55)
        if (node === hovered) color = color.brighter()
        g2.color = color
        g2.fillRect(rect.x, rect.y, rect.width, rect.height)
        val fm = getFontMetrics(font)
        val pad = JBUI.scale(3)
        if (rect.width < fm.charWidth('W') * 2 + pad * 2) return
        val label = if (node.parent == null) LuaDebugBundle.message("tab.profiler.flame.root", node.total) else node.name
        val text = fit(label, rect.width - pad * 2)
        g2.color = if (dim) Color(0x40, 0x40, 0x40) else Color.BLACK
        g2.font = font
        g2.drawString(text, rect.x + pad, rect.y + (h - fm.height) / 2 + fm.ascent)
    }

    private fun fit(text: String, room: Int): String {
        val fm = getFontMetrics(font)
        if (fm.stringWidth(text) <= room) return text
        var n = text.length
        while (n > 0 && fm.stringWidth(text.substring(0, n) + "…") > room) n--
        return if (n == 0) "" else text.substring(0, n) + "…"
    }

    private fun nodeAt(x: Int, y: Int): LuaCallNode? = painted.lastOrNull { it.first.contains(x, y) }?.second

    override fun getToolTipText(event: MouseEvent): String? {
        val node = nodeAt(event.x, event.y) ?: return null
        val r = root ?: return null
        val total = String.format(Locale.ROOT, "%.2f", 100.0 * node.total / r.total)
        val self = String.format(Locale.ROOT, "%.2f", 100.0 * node.self / r.total)
        val name = escape(node.name)
        val location = node.location.takeIf { it.isNotEmpty() }?.let { "<br>${escape(it)}" } ?: ""
        return "<html><b>$name</b>$location<br>" + LuaDebugBundle.message("tab.profiler.flame.tip", total, node.total, self, node.self) + "</html>"
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colorOf(node: LuaCallNode): Color {
        if (node.parent == null) return JBColor(Color(0xC8, 0xC8, 0xC8), Color(0x80, 0x80, 0x80))
        if (node.location == "[C]") return Color(0xB4, 0xC8, 0xDC)
        if (node.label.startsWith("<")) return Color(0xD0, 0xD0, 0xD0)
        // 同一个函数在不同位置颜色相同：按名字哈希取暖色
        val h = node.label.hashCode()
        val hue = 0.0f + ((h ushr 8) and 0xff) / 255f * 0.12f
        val sat = 0.45f + (h and 0x3f) / 63f * 0.25f
        return Color.getHSBColor(hue, sat, if (JBColor.isBright()) 0.95f else 0.85f)
    }

    private fun blend(a: Color, b: Color, t: Double) = Color(
        (a.red * (1 - t) + b.red * t).toInt(), (a.green * (1 - t) + b.green * t).toInt(), (a.blue * (1 - t) + b.blue * t).toInt()
    )
}

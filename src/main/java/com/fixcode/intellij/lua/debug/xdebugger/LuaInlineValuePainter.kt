package com.fixcode.intellij.lua.debug.xdebugger

import com.fixcode.intellij.lua.debug.xdebugger.LuaInlineValueScanner.Entry
import com.fixcode.intellij.lua.lang.LuaFileType
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import java.awt.Color
import java.awt.Font

/**
 * 在 Lua 源码行尾绘制调试器变量内联值预览，类似 Java 的 inline values。
 *
 * 规则：
 * 1. 仅对当前 session 的栈帧所在 Lua 文件生效；视口外的行 IDE 不会调用。
 * 2. 只展示"被赋值/被声明"的变量名（LHS）：
 *    - `local a = ...`         → 显示 a
 *    - `a = ...`               → 显示 a
 *    - `self.x = ...`          → 显示 x（取末段字段名）
 *    - `local t = { k = v }`   → 在 k 这一行显示 k 的值（递归到字段）
 * 3. value 为 **table** 的一律不展示（避免噪声、也避免无谓解析），留给 Variables 面板。
 * 4. Painter 只读取暂停（或切换栈帧）时取好的不可变快照（[LuaInlineValueStore]），绘制时不请求 runtime。
 * 5. 挑变量的规则在 [LuaInlineValueScanner]：只扫本行文本的 token，不依赖语法树和索引。
 */
class LuaInlineValuePainter : EditorLinePainter() {

    override fun getLineExtensions(
        project: Project,
        file: VirtualFile,
        lineNumber: Int
    ): MutableCollection<LineExtensionInfo>? {
        if (file.fileType != LuaFileType) return null

        val session = XDebuggerManager.getInstance(project).currentSession ?: return null
        if (session.currentStackFrame == null) return null
        val snapshot = LuaInlineValueStore.getInstance(project).get(session, file) ?: return null

        // 绘制时平台软禁止拿锁（withLocksSoftlyProhibited），getDocument 要读锁会报错；
        // 正在绘制的文件一定开着编辑器，文档已经在缓存里，取缓存的就够了。
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        if (lineNumber < 0 || lineNumber >= document.lineCount) return null

        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val scanStart = codeStartOnLine(document, lineStart, lineEnd) ?: return null

        val entries = LuaInlineValueScanner.scan(document.immutableCharSequence.subSequence(scanStart, lineEnd))
        if (entries.isEmpty()) return null

        val topVars = snapshot.variables
        if (topVars.isEmpty() && entries.all { it.literalText == null }) return null

        val results = mutableListOf<LineExtensionInfo>()
        val attrs = getInlineAttributes()
        val used = hashSetOf<String>()
        var first = true
        for (entry in entries) {
            // 去重 displayName，避免同一行多次出现同名变量造成重复提示
            if (!used.add(entry.displayName)) continue

            // 取值策略：
            // 1. 首选 pathSegments（LHS 路径），取到非 table 值即用
            // 2. 取不到时（LHS 尚未赋值 / 中间节点未缓存等）→ fallbackPath（RHS 路径）
            // 3. 仍取不到时 → literalText（RHS 字面量文本）
            // 4. 都没有 → 跳过
            val valueText: String = resolveEntryValue(entry, topVars) ?: continue

            val prefix = if (first) "  " else ", "
            first = false
            results.add(LineExtensionInfo("$prefix${entry.displayName} = $valueText", attrs))
        }

        return if (results.isEmpty()) null else results
    }

    /**
     * 按优先级解析 Entry 的展示文本：
     * 1. `pathSegments`（主路径，一般为 LHS / 主路径）
     * 2. `fallbackPath`（备选路径，一般为 RHS 路径）
     * 3. `literalText`（RHS 字面量兜底）
     * 任一级命中即返回。table 类型会被跳过（继续试下一级）；nil 会被正常渲染为 `nil`。
     *
     */
    private fun resolveEntryValue(
        entry: Entry,
        topVars: Map<String, DebugValueSnapshot>
    ): String? {
        // 1. 主路径
        if (entry.pathSegments != null) {
            val value = resolvePath(topVars, entry.pathSegments)
            if (value != null && !value.isContainer) {
                renderValue(value)?.let { return it }
            }
        }
        // 2. 备选路径
        if (entry.fallbackPath != null) {
            val value = resolvePath(topVars, entry.fallbackPath)
            if (value != null && !value.isContainer) {
                renderValue(value)?.let { return it }
            }
        }
        // 3. 字面量
        if (entry.literalText != null) {
            return entry.literalText
        }
        return null
    }

    private fun renderValue(value: DebugValueSnapshot): String? {
        val text = if (value.type.equals("nil", true) || value.type.equals("tnil", true)) "nil" else value.value
        return text?.takeIf(String::isNotEmpty)?.let { if (it.length > 80) it.take(77) + "..." else it }
    }

    /**
     * 本行从哪里开始是代码。只扫一行文本时看不出这一行是不是在跨行的长注释 / 长字符串中间，
     * 里面的单词会被当成变量名；所以看 [LuaMultilineSpans]（整个文件扫一次词法，按修改戳缓存）：
     * 行首落在从上面几行开始的注释或字符串里时，从它结束处开始扫，整行都在里面就不显示。
     * 不用 PSI：绘制线程里拿不到读锁。
     */
    private fun codeStartOnLine(document: Document, lineStart: Int, lineEnd: Int): Int? {
        val end = LuaMultilineSpans.endOfSpanCovering(document, lineStart)
        if (end < 0) return lineStart
        return if (end >= lineEnd) null else end
    }

    // ---------- Snapshot 侧：只读路径解析 ----------

    private fun resolvePath(
        topVars: Map<String, DebugValueSnapshot>,
        path: List<String>
    ): DebugValueSnapshot? {
        if (path.isEmpty()) return null
        var current = topVars[path[0]] ?: return null
        for (i in 1 until path.size) {
            if (current.type.equals("nil", true) || current.type.equals("tnil", true)) return current
            current = current.children[path[i]] ?: return null
        }
        return current
    }

    private fun getInlineAttributes(): TextAttributes {
        // 不使用 INLINE_PARAMETER_HINT（它在多数主题里带有灰色背景）。
        // 这里手动拼一个：前景取主题里的 LINE_COMMENT 颜色（灰色、协调），
        // 背景显式置为 null（无背景），字体置为斜体。
        val scheme = EditorColorsManager.getInstance().globalScheme
        val commentAttr = scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)
        val fg = commentAttr?.foregroundColor ?: Color(0x868686)
        return TextAttributes(fg, null, null, EffectType.BOXED, Font.ITALIC)
    }
}

/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.tang.intellij.lua.debugger

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.xdebugger.XDebuggerManager
import com.tang.intellij.lua.lang.LuaFileType
import com.tang.intellij.lua.psi.LuaAssignStat
import com.tang.intellij.lua.psi.LuaCallExpr
import com.tang.intellij.lua.psi.LuaExpr
import com.tang.intellij.lua.psi.LuaIndexExpr
import com.tang.intellij.lua.psi.LuaListArgs
import com.tang.intellij.lua.psi.LuaLiteralExpr
import com.tang.intellij.lua.psi.LuaLocalDef
import com.tang.intellij.lua.psi.LuaNameExpr
import com.tang.intellij.lua.psi.LuaParamNameDef
import com.tang.intellij.lua.psi.LuaTableExpr
import com.tang.intellij.lua.psi.LuaTableField
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
 * 4. Painter 只读取暂停时生成的不可变快照，不调用 XValue 或后端 eval。
 */
class LuaEditorLinePainter : EditorLinePainter() {

    /**
     * 行内一个待绘制条目。
     *
     * - [displayName]：展示用的短名（e.g. `isAble`）
     * - [pathSegments]：从 frame 顶层变量起的访问链（e.g. `[self, x, isAble]`）
     *   当 pathSegments 为 null 时，使用 [literalText] 直接渲染（适配 table 字面量里的字面值字段）
     * - [fallbackPath]：可选的备选路径。首选路径（pathSegments）取不到时再尝试这个。
     *   典型场景：赋值行 `self.x = y`，首选 LHS `self.x`（展示当前值），
     *   如果 LHS 不存在则回退到 RHS `y`（展示即将写入的值）。
     */
    private data class Entry(
        val displayName: String,
        val pathSegments: List<String>?,
        val literalText: String? = null,
        val fallbackPath: List<String>? = null
    )

    override fun getLineExtensions(
        project: Project,
        file: VirtualFile,
        lineNumber: Int
    ): MutableCollection<LineExtensionInfo>? {
        if (file.fileType != LuaFileType.INSTANCE) return null

        val session = XDebuggerManager.getInstance(project).currentSession ?: return null
        if (session.currentStackFrame == null) return null
        val snapshot = InlineDebugSnapshotStore.getInstance(project).get(session, file) ?: return null

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (lineNumber < 0 || lineNumber >= document.lineCount) return null

        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)

        val entries = collectLineEntries(psiFile, lineStart, lineEnd)
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

    // ---------- PSI 扫描：挑出需要展示的条目 ----------

    private fun collectLineEntries(
        psiFile: PsiFile,
        lineStart: Int,
        lineEnd: Int
    ): List<Entry> {
        val result = mutableListOf<Entry>()
        val handledRanges = mutableListOf<IntRange>()

        // 先处理 local / assign / 函数声明参数。注意一个 local / assign 语句可能跨多行
        // （例如 local t = { ... }），但本 painter 是按"行"回调的，所以只需要收集
        // 与本行有交集的那部分（LHS 所在行、或 table 字面量字段所在行、或函数签名参数所在行）。
        val seenParams = hashSetOf<String>()
        var el: PsiElement? = psiFile.findElementAt(lineStart)
        while (el != null && el.textRange.startOffset < lineEnd) {
            // 函数参数声明：`function foo(a, b, c)` —— 每个 a/b/c 是 LuaParamNameDef，
            // 本身就是 frame locals 顶层可见变量，直接用参数名即可。
            val paramDef = PsiTreeUtil.getParentOfType(el, LuaParamNameDef::class.java, false)
            if (paramDef != null && paramDef.textRange.startOffset in lineStart until lineEnd) {
                handleParamNameDef(paramDef, lineStart, lineEnd, seenParams, result)
                // 跳到 param 之后继续扫
                el = advanceAfter(psiFile, paramDef, el)
                continue
            }

            val localDef = PsiTreeUtil.getParentOfType(el, LuaLocalDef::class.java, false)
            val assignStat = PsiTreeUtil.getParentOfType(el, LuaAssignStat::class.java, false)

            when {
                localDef != null -> {
                    handleLocalDef(localDef, lineStart, lineEnd, result)
                    handledRanges += localDef.textRange.let { it.startOffset..it.endOffset }
                    el = advanceAfter(psiFile, localDef, el)
                }
                assignStat != null -> {
                    handleAssignStat(assignStat, lineStart, lineEnd, result)
                    handledRanges += assignStat.textRange.let { it.startOffset..it.endOffset }
                    el = advanceAfter(psiFile, assignStat, el)
                }
                else -> {
                    el = PsiTreeUtil.nextLeaf(el)
                }
            }
        }

        // 额外：扫描本行所有 LuaCallExpr 的实参，把能解析为路径（NameExpr / 纯字段 IndexExpr）
        // 的实参产出为 Entry。适用于 `self:m(self.x, y)` / `foo(bar.baz)` 等场景。
        // 注意：call 可能嵌套在 assign/local 的 RHS 里，我们仍然扫描——这样
        // `local r = self:m(self.x)` 里的 `self.x` 也能展示；重复会在后面去重。
        collectCallArgEntries(psiFile, lineStart, lineEnd, result)

        // 兜底：裸表达式行（例如 `foo()` / `x`）
        if (result.isEmpty()) {
            var e: PsiElement? = psiFile.findElementAt(lineStart)
            while (e != null && e.textRange.startOffset < lineEnd) {
                val offset = e.textRange.startOffset
                val inHandled = handledRanges.any { offset in it }
                if (!inHandled) {
                    val parent = e.parent
                    if (parent is LuaNameExpr && !isPartOfLargerExpr(parent)) {
                        val entry = buildEntryFromExpr(parent)
                        if (entry != null) result += entry
                    }
                }
                e = PsiTreeUtil.nextLeaf(e) ?: break
            }
        }

        // 按 (displayName + pathSegments) 去重，保持首次出现顺序
        if (result.size > 1) {
            val seen = HashSet<String>()
            val dedup = ArrayList<Entry>(result.size)
            for (entry in result) {
                val key = entry.displayName + "|" + (entry.pathSegments?.joinToString(".") ?: "#lit:${entry.literalText}")
                if (seen.add(key)) dedup += entry
            }
            return dedup
        }
        return result
    }

    /**
     * 扫本行所有 [LuaCallExpr]，把其实参里能解析为路径的表达式加入 [out]。
     * 支持：
     *   - `foo(x)`                 → 产出 x
     *   - `foo(self.x)`            → 产出 x（路径 [self, x]）
     *   - `self:m(self.a.b, c)`    → 产出 b（路径 [self, a, b]）和 c
     * 不处理：
     *   - 实参是字面量 / 下标表达式 / 嵌套 call / 运算表达式 —— 无法解析为简单路径
     *   - 单独的 `self` 实参（噪声）
     */
    private fun collectCallArgEntries(
        psiFile: PsiFile,
        lineStart: Int,
        lineEnd: Int,
        out: MutableList<Entry>
    ) {
        // 用 PsiTreeUtil 递归收集本行出现的所有 LuaCallExpr（限制在行范围内）
        val visited = HashSet<LuaCallExpr>()
        var el: PsiElement? = psiFile.findElementAt(lineStart)
        while (el != null && el.textRange.startOffset < lineEnd) {
            val call = PsiTreeUtil.getParentOfType(el, LuaCallExpr::class.java, false)
            if (call != null && visited.add(call)) {
                val args = call.args
                if (args is LuaListArgs) {
                    for (argExpr in args.exprList) {
                        // 实参必须整个落在本行范围内（多行调用的其它行由各自的 painter 回调处理）
                        val r = argExpr.textRange
                        if (r.startOffset >= lineEnd || r.endOffset <= lineStart) continue
                        val entry = buildEntryFromExpr(argExpr) ?: continue
                        out += entry
                    }
                }
            }
            el = PsiTreeUtil.nextLeaf(el) ?: break
        }
    }

    private fun advanceAfter(psiFile: PsiFile, stmt: PsiElement, cur: PsiElement): PsiElement? {
        val r = stmt.textRange
        val next = psiFile.findElementAt(r.endOffset)
        return if (next != null && next !== cur) next else PsiTreeUtil.nextLeaf(cur)
    }

    /** `local a, b = expr1, expr2 | tableExpr`：LHS 是 NameDef 列表。 */
    private fun handleLocalDef(
        localDef: LuaLocalDef,
        lineStart: Int,
        lineEnd: Int,
        out: MutableList<Entry>
    ) {
        val nameList = localDef.nameList?.nameDefList ?: emptyList()
        val valueList = localDef.exprList?.exprList ?: emptyList()

        // 只处理 LHS 落在本行的 NameDef。
        // 对每个 LHS：优先把 RHS 路径作为取值源（断点位置一般是"本行执行前"，
        // LHS 可能尚未赋值，读取 LHS 会失败；RHS 通常已经是可见变量，能直接取到值）。
        for ((idx, nd) in nameList.withIndex()) {
            val ndOffset = nd.textRange.startOffset
            if (ndOffset !in lineStart until lineEnd) continue
            val name = nd.name
            if (!isDisplayableName(name)) continue

            val rhsExpr = valueList.getOrNull(idx)
            val entry = buildAssignEntry(displayName = name, lhsPath = listOf(name), rhsExpr = rhsExpr)
            if (entry != null) out += entry
        }

        // 如果 RHS 是单个 table 字面量，递归处理其字段（按行过滤）
        if (valueList.size == 1 && nameList.size == 1) {
            val rhs = valueList[0]
            if (rhs is LuaTableExpr) {
                val topName = nameList[0].name
                if (isDisplayableName(topName)) {
                    collectTableFieldEntries(rhs, listOf(topName), lineStart, lineEnd, out)
                }
            }
        }
    }

    /**
     * 函数签名里的一个参数：`function foo(a, b, c)` 中的 `a` / `b` / `c`。
     * 参数本身就是 frame locals 顶层可见变量，直接用参数名即可取到值。
     * 使用 [seenParams] 去重，避免同一行多个 param leaf 反复被识别到。
     */
    private fun handleParamNameDef(
        paramDef: LuaParamNameDef,
        lineStart: Int,
        lineEnd: Int,
        seenParams: MutableSet<String>,
        out: MutableList<Entry>
    ) {
        val offset = paramDef.textRange.startOffset
        if (offset !in lineStart until lineEnd) return
        val name = paramDef.name
        if (!isDisplayableName(name)) return
        if (!seenParams.add(name)) return
        out += Entry(displayName = name, pathSegments = listOf(name))
    }

    /** `varList = valueList`：LHS 可能是 NameExpr 或 IndexExpr。 */
    private fun handleAssignStat(
        assignStat: LuaAssignStat,
        lineStart: Int,
        lineEnd: Int,
        out: MutableList<Entry>
    ) {
        val lhsList = assignStat.varExprList.exprList
        val rhsList = assignStat.valueExprList?.exprList ?: emptyList()

        // 对每个 LHS：displayName 取 LHS 末段（如 self.x → x），但取值优先用 RHS。
        // 原因：断点位置一般是"本行执行前"，LHS 还没被赋值（读 self.x 会得到 nil 或找不到），
        // 而 RHS 此时通常是 locals/上游字段，可直接取到。
        for ((idx, lhs) in lhsList.withIndex()) {
            val lhsOffset = lhs.textRange.startOffset
            if (lhsOffset !in lineStart until lineEnd) continue
            val lhsPath = buildPathFromExpr(lhs) ?: continue
            val display = lhsPath.last()
            // self 本身不展示；纯 `self` 路径直接跳
            if (lhsPath.size == 1 && lhsPath[0] == "self") continue

            val rhsExpr = rhsList.getOrNull(idx)
            val entry = buildAssignEntry(displayName = display, lhsPath = lhsPath, rhsExpr = rhsExpr)
            if (entry != null) out += entry
        }

        // RHS 单个 table 字面量 + LHS 单个 → 递归展开字段
        if (lhsList.size == 1 && rhsList.size == 1) {
            val rhs = rhsList[0]
            if (rhs is LuaTableExpr) {
                val pathFromLhs = buildPathFromExpr(lhsList[0])
                if (pathFromLhs != null) {
                    collectTableFieldEntries(rhs, pathFromLhs, lineStart, lineEnd, out)
                }
            }
        }
    }

    /**
     * 构造一个赋值行的 Entry。策略：
     * - **首选 LHS 路径**（反映"当前值 / 执行前状态"）。对很多情况（循环里的 `self.x = ...`、
     *   第二次进入这一行，或字段本来已存在）LHS 路径能直接查到值。
     * - **fallback 用 RHS**（"即将赋给 LHS 的值"）。当 LHS 还不存在（首次赋值）时，
     *   resolvePath 会返回 null，我们在渲染阶段自动回退到 RHS。
     * - 如果 RHS 是字面量，直接附带 literalText（无需下钻），用作 LHS 失败时的兜底。
     */
    private fun buildAssignEntry(
        displayName: String,
        lhsPath: List<String>,
        rhsExpr: LuaExpr?
    ): Entry? {
        // 提取 RHS 能用的 fallback：字面量文本 或 纯字段访问路径
        val rhsFallbackPath: List<String>? = when (rhsExpr) {
            is LuaNameExpr -> buildPathFromExpr(rhsExpr)
            is LuaIndexExpr -> buildPathFromExpr(rhsExpr)
            else -> null
        }
        val rhsLiteral: String? =
            (rhsExpr as? LuaLiteralExpr)?.text

        return Entry(
            displayName = displayName,
            pathSegments = lhsPath,
            literalText = rhsLiteral,
            fallbackPath = rhsFallbackPath
        )
    }

    /**
     * 对一个 table 字面量里的每个字段：
     * - 若 field 自身不在本行范围，跳过（该行用不到）
     * - 若 field 的 RHS 是字面量（string/number/bool/nil），直接用字面量文本
     * - 若 RHS 是 LuaNameExpr / LuaIndexExpr，构造访问链，交给 resolvePath 查缓存值
     * - 若 RHS 是嵌套 table / function，跳过（本规则不展示 table）
     */
    private fun collectTableFieldEntries(
        tableExpr: LuaTableExpr,
        @Suppress("UNUSED_PARAMETER") parentPath: List<String>,
        lineStart: Int,
        lineEnd: Int,
        out: MutableList<Entry>
    ) {
        for (field in tableExpr.tableFieldList) {
            val foff = field.textRange.startOffset
            if (foff !in lineStart until lineEnd) continue

            val fieldName = field.name ?: continue
            if (!isDisplayableName(fieldName)) continue

            val rhsExpr = field.exprList.lastOrNull() ?: continue

            when (rhsExpr) {
                is LuaLiteralExpr -> {
                    val txt = rhsExpr.text ?: continue
                    out += Entry(displayName = fieldName, pathSegments = null, literalText = txt)
                }
                is LuaNameExpr -> {
                    val nm = rhsExpr.name
                    if (isDisplayableName(nm)) {
                        out += Entry(displayName = fieldName, pathSegments = listOf(nm))
                    }
                }
                is LuaIndexExpr -> {
                    val path = buildPathFromExpr(rhsExpr)
                    if (path != null) {
                        out += Entry(displayName = fieldName, pathSegments = path)
                    }
                }
                // LuaTableExpr / LuaClosureExpr / 运算等 → 跳过
                else -> Unit
            }
        }
    }

    /**
     * 从 `LuaNameExpr` / `LuaIndexExpr` 构造一条访问链：
     * - `foo`             → [foo]
     * - `self.x.y`        → [self, x, y]
     * - `self.x[1]` / `self:method()` 等非纯点/冒号访问 → null
     */
    private fun buildPathFromExpr(expr: LuaExpr): List<String>? {
        return when (expr) {
            is LuaNameExpr -> {
                // 允许 `self` 作为路径首段（`self.x.y`），但它单独出现时由上层 (buildEntryFromExpr /
                // handleAssignStat) 决定是否跳过显示。
                val n = expr.name
                if (n.isNullOrEmpty()) null
                else if (n == "self") listOf("self")
                else listOf(n).takeIf { isDisplayableName(it[0]) }
            }
            is LuaIndexExpr -> {
                // 只接受形如 a.b.c 或 a:b 链（纯字段访问）；下标 / 表达式索引 → null
                if (expr.lbrack != null) return null
                val name = expr.name ?: return null
                if (!isDisplayableName(name)) return null
                val prefix = expr.exprList.firstOrNull() ?: return null
                val parentPath = buildPathFromExpr(prefix) ?: return null
                parentPath + name
            }
            else -> null
        }
    }

    /** 裸行兜底时：从一个表达式（通常是 LuaNameExpr）构造 Entry。 */
    private fun buildEntryFromExpr(expr: LuaExpr): Entry? {
        val path = buildPathFromExpr(expr) ?: return null
        val last = path.last()
        // self 本身不展示；链首若是 self，显示末段字段（例如 self.isAble → isAble）
        if (path.size == 1 && path[0] == "self") return null
        return Entry(displayName = last, pathSegments = path)
    }

    private fun isPartOfLargerExpr(nameExpr: LuaNameExpr): Boolean {
        // 避免把 a.b 里的 a 当成独立 NameExpr（它会是 LuaIndexExpr 的子节点）
        val parent = nameExpr.parent
        return parent is LuaIndexExpr
    }

    private fun isDisplayableName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        if (name == "self") return false
        if (name.any { it == '.' || it == ':' || it == '[' }) return false
        return true
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

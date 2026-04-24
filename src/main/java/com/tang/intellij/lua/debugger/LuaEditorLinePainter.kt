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

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.Collections
import java.util.WeakHashMap
import com.tang.intellij.lua.debugger.emmy.LuaValueType
import com.tang.intellij.lua.debugger.emmy.value.LuaXValue
import com.tang.intellij.lua.debugger.emmy.value.TableXValue
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
 * 4. 绝不触发后端 eval：所有下钻都走 `TableXValue.cachedChildren`（已有缓存才往下），
 *    避免 UnLua __index / Class_Index 崩溃路径。
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

    companion object {
        /**
         * 已经为其发起过 `computeChildren` 预取的 TableXValue 集合。WeakHashMap 保证
         * frame 切换 / 调试会话结束后，相关对象被 GC 时自动移除。
         * 防止对同一 table 反复发送 EvalReq。
         *
         * 注意：TUSERDATA 绝不能加入此集合（不做预取），因为 UnLua 的 __index 元方法
         * 在某些上下文（line hook、对象已释放）会崩溃 Lua 进程。
         */
        private val prefetchedTables: MutableSet<TableXValue> =
            Collections.newSetFromMap(WeakHashMap())
    }

    override fun getLineExtensions(
        project: Project,
        file: VirtualFile,
        lineNumber: Int
    ): MutableCollection<LineExtensionInfo>? {
        if (file.fileType != LuaFileType.INSTANCE) return null

        val session = XDebuggerManager.getInstance(project).currentSession ?: return null
        val frame: XStackFrame = session.currentStackFrame ?: return null

        // 栈帧必须在当前文件里；只在该文件范围内绘制 inline values
        val framePos = frame.sourcePosition ?: return null
        if (framePos.file != file) return null

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (lineNumber < 0 || lineNumber >= document.lineCount) return null

        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)

        val entries = collectLineEntries(psiFile, lineStart, lineEnd)
        if (entries.isEmpty()) return null

        val topVars = collectTopLevelVariables(frame)
        if (topVars.isEmpty() && entries.all { it.literalText == null }) return null

        val results = mutableListOf<LineExtensionInfo>()
        val attrs = getInlineAttributes()
        val used = hashSetOf<String>()
        // 本轮扫描中遇到的"cachedChildren 为空/不全的中间 TableXValue"，返回前统一异步预取。
        // 预取完成后我们会 restart DaemonCodeAnalyzer，下次编辑器重绘时 painter 能命中缓存。
        val pendingPrefetch = linkedSetOf<TableXValue>()

        var first = true
        for (entry in entries) {
            // 去重 displayName，避免同一行多次出现同名变量造成重复提示
            if (!used.add(entry.displayName)) continue

            // 取值策略：
            // 1. 首选 pathSegments（LHS 路径），取到非 table 值即用
            // 2. 取不到时（LHS 尚未赋值 / 中间节点未缓存等）→ fallbackPath（RHS 路径）
            // 3. 仍取不到时 → literalText（RHS 字面量文本）
            // 4. 都没有 → 跳过
            val valueText: String = resolveEntryValue(entry, topVars, pendingPrefetch) ?: continue

            val prefix = if (first) "  " else ", "
            first = false
            results.add(LineExtensionInfo("$prefix${entry.displayName} = $valueText", attrs))
        }

        // 触发缺失中间节点的异步预取（一次性），完成后请求编辑器重绘
        if (pendingPrefetch.isNotEmpty()) {
            schedulePrefetch(project, psiFile, pendingPrefetch)
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
     * 若中间节点的 children 还未加载，会把该节点登记到 [pendingPrefetch]，
     * 稍后统一发起异步 eval；本次无值，等下一轮重绘再试。
     */
    private fun resolveEntryValue(
        entry: Entry,
        topVars: Map<String, XValue>,
        pendingPrefetch: MutableSet<TableXValue>
    ): String? {
        // 1. 主路径
        if (entry.pathSegments != null) {
            val xv = resolvePath(topVars, entry.pathSegments, pendingPrefetch)
            if (xv != null && !isTableValue(xv)) {
                renderXValue(xv)?.let { return it }
            }
        }
        // 2. 备选路径
        if (entry.fallbackPath != null) {
            val xv = resolvePath(topVars, entry.fallbackPath, pendingPrefetch)
            if (xv != null && !isTableValue(xv)) {
                renderXValue(xv)?.let { return it }
            }
        }
        // 3. 字面量
        if (entry.literalText != null) {
            return entry.literalText
        }
        return null
    }

    /**
     * 渲染 XValue 的文本。针对 nil 做了特殊处理：有些后端对 TNIL 不提供有意义的 presentation
     * （比如空字符串），导致 inline 无法展示。这里直接硬编码为 "nil"。
     */
    private fun renderXValue(xv: XValue): String? {
        val lx = xv as? LuaXValue
        if (lx != null && lx.value.valueTypeValue == LuaValueType.TNIL) {
            return "nil"
        }
        return renderInlineValueText(xv)
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

    // ---------- XValue 侧：路径解析 + 类型判断 + 文本渲染 ----------

    /**
     * 沿 [path] 在顶层变量表里逐层下钻到末端 XValue。
     * 过程中只读取 `TableXValue.cachedChildren`（本地缓存），不主动触发 eval。
     * 任何一段查不到就返回 null；若中间/起始 TableXValue 的 children 未加载，
     * 则登记到 [pendingPrefetch]，稍后统一异步触发 computeChildren。
     *
     * 特殊处理：TableXValue 有时 cachedChildren 非空但只含 metatable 伪条目
     * （name 以 `(metatable` 开头），这时视作"真字段尚未加载"，也登记预取。
     */
    private fun resolvePath(
        topVars: Map<String, XValue>,
        path: List<String>,
        pendingPrefetch: MutableSet<TableXValue>
    ): XValue? {
        if (path.isEmpty()) return null
        var cur: XValue = topVars[path[0]] ?: return null
        for (i in 1 until path.size) {
            val seg = path[i]
            // 中间节点是 nil：在 Lua 里 `nil.x` 会崩，但调试器 inline preview 视角下，
            // 把"父链已 nil 推导整条链为 nil"是对用户最有价值的提示（能直观看到
            // `params.ReportData` 是 nil 时，所有 `self.X = params.ReportData.Y` 行
            // 都以 nil 显示）。所以在此短路返回当前的 nil XValue。
            if ((cur as? LuaXValue)?.value?.valueTypeValue == LuaValueType.TNIL) {
                return cur
            }
            val tv = cur as? TableXValue ?: return null
            val cached = tv.cachedChildren
            val hasReal = cached.any { !isMetatablePseudo(it.value.name) }
            if (!hasReal) {
                // 中间节点真字段未加载 —— 登记预取，本次先返回 null 不渲染。
                // TUSERDATA 绝不预取（UnLua __index 防崩）。
                if (tv.value.valueTypeValue != LuaValueType.TUSERDATA) {
                    pendingPrefetch.add(tv)
                }
                return null
            }
            val next = cached.firstOrNull { it.value.name == seg } ?: return null
            cur = next
        }
        return cur
    }

    private fun isMetatablePseudo(name: String): Boolean =
        name.startsWith("(metatable")

    /**
     * 对本轮扫描中发现的 "真字段未缓存" 的中间 TableXValue，批量异步触发一次
     * `computeChildren`（会发 EvalReq，回调里 `children` 被填充）。
     * 每个 TableXValue 只请求一次（由 [prefetchedTables] 去重）。
     * 任一节点加载完成后，restart DaemonCodeAnalyzer 让 painter 用新缓存重绘一次。
     *
     * 安全性：painter 发 eval 使用独立的假 XCompositeNode；`TableXValue.computeChildren`
     * 每次调用都会发送新的 EvalReq（协议 seq 不同，各自回调），painter 的请求不会
     * 拦截 / 吞掉变量面板的请求，因此不会影响正常展开。
     */
    private fun schedulePrefetch(
        project: Project,
        psiFile: PsiFile,
        targets: Set<TableXValue>
    ) {
        val toRequest = targets.filter { prefetchedTables.add(it) }
        if (toRequest.isEmpty()) return

        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val node = object : XCompositeNode {
                override fun addChildren(children: XValueChildrenList, last: Boolean) {}
                override fun tooManyChildren(remaining: Int) {}
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) {}
                override fun setErrorMessage(
                    errorMessage: String,
                    link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?
                ) {}
                override fun setMessage(
                    message: String,
                    icon: javax.swing.Icon?,
                    attributes: SimpleTextAttributes,
                    link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?
                ) {}
            }
            for (tv in toRequest) {
                try {
                    tv.computeChildren(node)
                } catch (_: Throwable) {
                    // 预取失败不影响主流程
                }
            }
            // eval 是异步完成的，children 在随后才被填充。
            // 短延迟后 restart DaemonCodeAnalyzer，让 painter 再跑一次用上新缓存。
            app.invokeLater({
                if (project.isDisposed) return@invokeLater
                try {
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                } catch (_: Throwable) {}
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }

    private fun isTableValue(xv: XValue): Boolean {
        val lx = xv as? LuaXValue ?: return false
        val t = lx.value.valueTypeValue
        return t == LuaValueType.TTABLE
    }

    /**
     * 同步收集 frame 顶层变量。Lua frame 的 computeChildren 是同步的（addChildren 本地列表）。
     */
    private fun collectTopLevelVariables(frame: XStackFrame): Map<String, XValue> {
        val result = linkedMapOf<String, XValue>()
        val node = object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) {
                    val name = children.getName(i)
                    val v = children.getValue(i)
                    if (v is XValue) result[name] = v
                }
            }
            override fun tooManyChildren(remaining: Int) {}
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) {}
            override fun setErrorMessage(
                errorMessage: String,
                link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?
            ) {}
            override fun setMessage(
                message: String,
                icon: javax.swing.Icon?,
                attributes: SimpleTextAttributes,
                link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?
            ) {}
        }
        try {
            frame.computeChildren(node)
        } catch (_: Throwable) {
            return emptyMap()
        }
        return result
    }

    /** 同步获取 XValue 的 presentation 字符串（不触发 eval）。 */
    private fun renderInlineValueText(xValue: XValue): String? {
        val buf = StringBuilder()
        var hasPresentation = false
        val node = object : XValueNode {
            override fun setPresentation(
                icon: javax.swing.Icon?,
                presentation: XValuePresentation,
                hasChildren: Boolean
            ) {
                val renderer = object : XValuePresentation.XValueTextRenderer {
                    override fun renderValue(value: String) { buf.append(value) }
                    override fun renderStringValue(value: String) {
                        buf.append('"').append(value).append('"')
                    }
                    override fun renderNumericValue(value: String) { buf.append(value) }
                    override fun renderKeywordValue(value: String) { buf.append(value) }
                    override fun renderValue(
                        value: String,
                        key: com.intellij.openapi.editor.colors.TextAttributesKey
                    ) { buf.append(value) }
                    override fun renderStringValue(
                        value: String,
                        additionalSpecialCharsToHighlight: String?,
                        maxLength: Int
                    ) { buf.append('"').append(value).append('"') }
                    override fun renderComment(comment: String) {}
                    override fun renderSpecialSymbol(symbol: String) { buf.append(symbol) }
                    override fun renderError(error: String) { buf.append(error) }
                }
                try { presentation.renderValue(renderer) } catch (_: Throwable) {}
                hasPresentation = true
            }
            override fun setPresentation(
                icon: javax.swing.Icon?,
                type: String?,
                value: String,
                hasChildren: Boolean
            ) {
                buf.append(value)
                hasPresentation = true
            }
            override fun setFullValueEvaluator(fullValueEvaluator: com.intellij.xdebugger.frame.XFullValueEvaluator) {}
            override fun isObsolete(): Boolean = false
        }
        try {
            xValue.computePresentation(node, XValuePlace.TREE)
        } catch (_: Throwable) {
            return null
        }
        if (!hasPresentation) return null
        val v = buf.toString()
        if (v.isEmpty()) return null
        return if (v.length > 80) v.substring(0, 77) + "..." else v
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

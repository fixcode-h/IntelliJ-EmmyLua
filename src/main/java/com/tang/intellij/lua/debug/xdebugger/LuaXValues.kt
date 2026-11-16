package com.tang.intellij.lua.debug.xdebugger

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XNavigatable
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XNumericValuePresentation
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import com.intellij.xdebugger.frame.presentation.XStringValuePresentation
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.tang.intellij.lua.debug.engine.DebugEngineException
import com.tang.intellij.lua.debug.engine.DebugSession
import com.tang.intellij.lua.debug.engine.EvalValue
import com.tang.intellij.lua.debug.engine.FrameHandle
import com.tang.intellij.lua.debug.engine.StaleReferenceException
import com.tang.intellij.lua.debug.engine.VariableContainer
import com.tang.intellij.lua.debug.protocol.Capabilities
import com.tang.intellij.lua.debug.protocol.EvalModes
import com.tang.intellij.lua.debug.protocol.VariableDto
import java.util.concurrent.CompletableFuture
import javax.swing.Icon

/** 一页取多少子项：设置页「每页条数」（默认 100）。更多的用「显示更多」按需再取。 */
internal fun childrenPage(): Int = LuaVariableViewSync.settings().pageSize

/** 查看完整字符串时最多取多少字节（协议 10.1 上限 1 MiB）。 */
private const val FULL_VALUE_MAX = 1024 * 1024

/** 求值结果和旧路径用的简单渲染：摘要直接用 runtime 给的 value，不在 IDE 侧拼（变量展示设计 15.2）。 */
internal fun presentationOf(type: String, value: String, typeName: String?): XValuePresentation = when (type) {
    "string" -> object : XStringValuePresentation(unquote(value)) {
        override fun getType() = typeName
    }
    "number" -> object : XNumericValuePresentation(value) {
        override fun getType() = typeName
    }
    else -> XRegularValuePresentation(value, typeName ?: type.takeIf { it == "table" || it == "userdata" || it == "function" })
}

private fun unquote(value: String): String =
    if (value.length >= 2 && value.first() == '"' && value.last() == '"') value.substring(1, value.length - 1) else value

/**
 * 变量树里一行的渲染（variable-view.md 第 3、15.2 节）：类型标签带数量，值按类型着色，
 * 已失效标红，被覆盖 / 继承来的灰掉，行尾灰字给 hint 和循环引用提示。
 */
internal class LuaVariablePresentation(
    private val v: VariableDto,
    private val cycle: Boolean,
    private val hexIntegers: Boolean
) : XValuePresentation() {
    override fun getType(): String? = typeLabel(v)

    override fun renderValue(renderer: XValueTextRenderer) {
        when {
            v.hasAttr("invalid") -> renderer.renderError(v.value)
            v.type == "string" -> renderer.renderValue(v.value, DefaultLanguageHighlighterColors.STRING)
            v.type == "number" -> renderer.renderNumericValue(withHex(v.value))
            v.type == "boolean" || v.type == "nil" -> renderer.renderKeywordValue(v.value)
            v.hasAttr("shadowed") -> renderer.renderComment(v.value)
            else -> renderer.renderValue(v.value)
        }
        val shownBytes = v.value.toByteArray(Charsets.UTF_8).size - 2
        if (v.type == "string" && v.length != null && v.length!! > shownBytes) renderer.renderComment("  （共 ${v.length} 字节）")
        if (cycle) renderer.renderComment("  ↻ 循环引用")
        if (v.hasAttr("shadowed")) renderer.renderComment("  被覆盖")
        if (v.hasAttr("helperError")) renderer.renderComment("  helper 出错")
        v.hint?.takeIf { it.isNotBlank() }?.let { renderer.renderComment("  $it") }
    }

    private fun withHex(text: String): String {
        if (!hexIntegers || v.kind == "float") return text
        val n = text.toLongOrNull() ?: return text
        return "$text (0x${java.lang.Long.toHexString(n).uppercase()})"
    }

    companion object {
        /** 有类型名用类型名；纯 table 用数量：`table[3]` 数组、`table{5}` 哈希、`table[3]{2}` 混合（第 3 节）。 */
        fun typeLabel(v: VariableDto): String? {
            v.typeName?.takeIf { it.isNotBlank() }?.let { return it }
            return when (v.type) {
                "table" -> {
                    val indexed = v.indexedCount ?: 0
                    val named = v.namedCount ?: 0
                    buildString {
                        append("table")
                        if (indexed > 0) append("[$indexed]")
                        if (named > 0) append("{$named}")
                    }
                }
                "userdata", "function", "thread" -> v.type
                else -> null
            }
        }
    }
}

/** 按 kind 选图标（15.4）。 */
internal fun iconOf(v: VariableDto): Icon = when {
    v.hasAttr("invalid") -> AllIcons.General.Error
    v.hasAttr("metamethod") -> AllIcons.Nodes.Method
    else -> when (v.kind) {
        "array", "ueContainer" -> AllIcons.Debugger.Db_array
        "record", "ueStruct" -> AllIcons.Json.Object
        "object", "ueObject" -> AllIcons.Nodes.Class
        "class", "module" -> AllIcons.Nodes.Class
        "function", "cfunction" -> AllIcons.Nodes.Function
        "coroutine" -> AllIcons.Debugger.ThreadSuspended
        "integer", "float", "ueEnum" -> AllIcons.Debugger.Db_primitive
        "group" -> AllIcons.Nodes.UpLevel
        else -> when (v.type) {
            "table" -> AllIcons.Json.Object
            "function" -> AllIcons.Nodes.Function
            "thread" -> AllIcons.Debugger.ThreadSuspended
            "string", "number", "boolean", "nil" -> AllIcons.Debugger.Db_primitive
            else -> AllIcons.Debugger.Value
        }
    }
}

/**
 * 展开时带下去的上下文：所在帧（查看完整值时在哪一帧求值）、祖先的 objectId（判断循环引用）、
 * 子项所属对象的类名（右键「固定 / 隐藏字段」写进显示规则文件的哪个类型，11.1）。
 */
internal data class ExpandContext(
    val frame: FrameHandle?,
    val ancestors: List<String> = emptyList(),
    val ownerType: String? = null
) {
    fun child(objectId: String?, ownerType: String? = null): ExpandContext =
        copy(ancestors = if (objectId.isNullOrEmpty()) ancestors else ancestors + objectId, ownerType = ownerType)
}

/** 有类名的对象才能按类型写显示规则；数组、普通 table、模块里的函数这类没有可匹配的类型。 */
private val RULE_KINDS = setOf("object", "record", "class", "module", "userdata")

/**
 * 子项加载：分页、过期时静默显示提示（协议第 18 节：STALE 不弹错误）。
 * [filter] 为 indexed 时只取数组段 [start, end)；named 时只取字段段；空时全部。
 */
internal fun loadChildren(
    engine: DebugSession,
    container: VariableContainer,
    node: XCompositeNode,
    start: Int,
    ctx: ExpandContext = ExpandContext(null),
    filter: String? = null,
    end: Int? = null
) {
    val count = if (end != null) minOf(childrenPage(), end - start) else childrenPage()
    engine.variables(container, start, count, filter).whenComplete { page, error ->
        if (node.isObsolete) return@whenComplete
        if (error != null) {
            node.setErrorMessage(messageOf(error))
            return@whenComplete
        }
        val list = XValueChildrenList()
        page.variables.forEach { handle ->
            val v = handle.variable
            // 宿主给的分组（UE 对象按声明类分组、组件、方法）是 kind=group 的普通子项，按顺序放在顶部
            if (v.kind == "group" && v.ref > 0) {
                handle.children()?.let { list.addTopGroup(LuaV3VariableGroup(engine, it, groupComment(v), ctx)) }
            } else {
                list.add(LuaV3Value(engine, v, container, ctx))
            }
        }
        // 继承成员、方法、元表放在底部分组（variable-view.md 5.4），只在第一页给
        page.groups.forEach { group -> group.children()?.let { list.addBottomGroup(LuaV3VariableGroup(engine, it, groupComment(group.variable), ctx)) } }
        val loaded = start + page.variables.size
        val remaining = if (end != null) {
            // 数组区间按下标算：空洞会让这一页少几项，但区间之外的不再取
            if (page.variables.isEmpty() || loaded >= end || loaded >= page.total) 0 else end - loaded
        } else {
            page.total - loaded
        }
        if (page.truncated && remaining <= 0 && page.variables.isNotEmpty()) {
            node.addChildren(list, false)
            node.tooManyChildren(1) { loadChildren(engine, container, node, loaded, ctx, filter, end) }
            return@whenComplete
        }
        node.addChildren(list, remaining <= 0)
        if (remaining > 0) node.tooManyChildren(remaining) { loadChildren(engine, container, node, loaded, ctx, filter, end) }
    }
}

/** 分组名后面的灰字：runtime 把数量放在 namedCount、来源放在 value（例「module UI.BagPanel」），不拼进名字 */
internal fun groupComment(v: VariableDto): String {
    val count = v.namedCount ?: v.length?.toInt()
    return listOfNotNull(count?.let { "($it)" }, v.value.takeIf { it.isNotBlank() }).joinToString("  ")
}

/**
 * 数组段超过一页时切成 `[1..100]`、`[101..200]`… 的区间分组（10.1）；每层最多 [childrenPage] 个子节点，
 * 再大就再套一层（10000 项 → 100 组 × 100 项）。
 */
internal fun rangeGroups(engine: DebugSession, container: VariableContainer, from: Int, to: Int, ctx: ExpandContext): List<XValueGroup> {
    val page = childrenPage()
    var chunk = page.toLong()
    while ((to - from + chunk - 1) / chunk > page) chunk *= page
    val groups = mutableListOf<XValueGroup>()
    var s = from.toLong()
    while (s < to) {
        val e = minOf(to.toLong(), s + chunk)
        groups.add(LuaV3RangeGroup(engine, container, s.toInt(), e.toInt(), ctx))
        s = e
    }
    return groups
}

/** 数组的一个下标区间。名字是 1 起的闭区间，和 Lua 下标一致。 */
class LuaV3RangeGroup internal constructor(
    private val engine: DebugSession,
    private val container: VariableContainer,
    private val from: Int,
    private val to: Int,
    private val ctx: ExpandContext
) : XValueGroup("[${from + 1}..$to]") {
    override fun computeChildren(node: XCompositeNode) {
        if (to - from > childrenPage()) {
            val list = XValueChildrenList()
            rangeGroups(engine, container, from, to, ctx).forEach { list.addTopGroup(it) }
            node.addChildren(list, true)
        } else {
            loadChildren(engine, container, node, from, ctx, "indexed", to)
        }
    }
}

internal fun messageOf(error: Throwable): String {
    val cause = DebugSession.unwrap(error)
    return when (cause) {
        is StaleReferenceException -> "已失效（游戏已继续运行）"
        is DebugEngineException -> cause.message ?: cause.code
        else -> cause.message ?: cause.toString()
    }
}

/** 一个变量。[parent] 是它所在的容器，修改值时用。 */
class LuaV3Value internal constructor(
    private val engine: DebugSession,
    private val variable: VariableDto,
    private val parent: VariableContainer?,
    private val ctx: ExpandContext = ExpandContext(null)
) : XNamedValue(variable.name) {
    private val cycle: Boolean = !variable.objectId.isNullOrEmpty() && variable.objectId in ctx.ancestors

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val settings = LuaVariableViewSync.settings()
        node.setPresentation(iconOf(variable), LuaVariablePresentation(variable, cycle, settings.hexIntegers), variable.expandable)
        fullValueEvaluator()?.let { node.setFullValueEvaluator(it) }
    }

    /** 字符串被截断时提供「查看完整内容」：按 evaluateName 在所在帧里取全文（4.2）。 */
    private fun fullValueEvaluator(): XFullValueEvaluator? {
        if (variable.type != "string") return null
        val length = variable.length ?: return null
        val expr = variable.evaluateName ?: return null
        val pause = parent?.pause ?: return null
        if (length <= variable.value.toByteArray(Charsets.UTF_8).size - 2) return null
        return object : XFullValueEvaluator() {
            override fun startEvaluation(callback: XFullValueEvaluationCallback) {
                engine.evaluate(pause, expr, ctx.frame, EvalModes.VALUE_PATH, "clipboard", FULL_VALUE_MAX).whenComplete { value, error ->
                    if (callback.isObsolete) return@whenComplete
                    if (error != null) callback.errorOccurred(messageOf(error)) else callback.evaluated(unescape(unquote(value.value)))
                }
            }
        }
    }

    override fun computeChildren(node: XCompositeNode) {
        val pause = parent?.pause ?: return node.addChildren(XValueChildrenList.EMPTY, true)
        if (!variable.expandable || variable.hasAttr("invalid")) return node.addChildren(XValueChildrenList.EMPTY, true)
        val container = VariableContainer(pause, variable.ref, variable.name)
        val childCtx = ctx.child(variable.objectId, variable.typeName?.takeIf { it.isNotBlank() && variable.kind in RULE_KINDS })
        val indexed = variable.indexedCount ?: 0
        if (indexed > childrenPage()) {
            // 大数组：数组段切成区间放在顶部，字段段（和底部分组）照常分页
            val list = XValueChildrenList()
            rangeGroups(engine, container, 0, indexed, childCtx).forEach { list.addTopGroup(it) }
            node.addChildren(list, false)
            loadChildren(engine, container, node, 0, childCtx, "named")
        } else {
            loadChildren(engine, container, node, 0, childCtx)
        }
    }

    override fun getEvaluationExpression(): String? = variable.evaluateName

    /** 右键「固定 / 隐藏字段」用：所属对象的类名和字符串键原文；不能按规则处理的返回 null。 */
    internal fun ruleTarget(): Pair<String, String>? {
        val owner = ctx.ownerType ?: return null
        if (variable.nameType != null && variable.nameType != "string") return null
        if (variable.kind == "group" || variable.hasAttr("metamethod")) return null
        return owner to fieldName(variable.name)
    }

    internal val pinned: Boolean get() = variable.hasAttr("pinned")

    override fun canNavigateToSource(): Boolean = variable.location != null

    override fun computeSourcePosition(navigatable: XNavigatable) {
        val location = variable.location ?: return navigatable.setSourcePosition(null)
        val path = engine.localPathOfChunk(location.chunkName) ?: return navigatable.setSourcePosition(null)
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return navigatable.setSourcePosition(null)
        navigatable.setSourcePosition(XDebuggerUtil.getInstance().createPosition(file, maxOf(0, location.line - 1)))
    }

    override fun getModifier(): XValueModifier? {
        val container = parent ?: return null
        if (!engine.hasCapability(Capabilities.SET_VARIABLE)) return null
        if (variable.hasAttr("readOnly") || variable.hasAttr("inherited") || variable.kind == "group") return null
        return object : XValueModifier() {
            override fun setValue(expression: com.intellij.xdebugger.XExpression, callback: XModificationCallback) {
                engine.setVariable(container, variable.name, expression.expression, EvalModes.LUA).whenComplete { _, error ->
                    if (error == null) callback.valueModified() else callback.errorOccurred(messageOf(error))
                }
            }

            override fun getInitialValueEditorText(): String? = variable.value.takeIf { variable.type != "table" }
        }
    }

    private companion object {
        /** 非标识符的字符串键显示成 `["end"]`，规则文件里写原文 */
        fun fieldName(name: String): String =
            if (name.startsWith("[\"") && name.endsWith("\"]")) unescape(name.substring(2, name.length - 2)) else name

        /** runtime 摘要里的转义还原成原文（查看完整内容时显示原文） */
        fun unescape(text: String): String {
            if ('\\' !in text) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c != '\\' || i + 1 >= text.length) {
                    out.append(c); i++; continue
                }
                when (val n = text[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    '"' -> { out.append('"'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    'x' -> {
                        val hex = text.substring(i + 2, minOf(text.length, i + 4))
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 2 && code != null) { out.append(code.toChar()); i += 4 } else { out.append(n); i += 2 }
                    }
                    else -> { out.append(n); i += 2 }
                }
            }
            return out.toString()
        }
    }
}

/** 求值结果。 */
class LuaV3EvalValue(private val engine: DebugSession, private val value: EvalValue) : XNamedValue(value.expression) {
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(AllIcons.Debugger.Value, presentationOf(value.type, value.value, value.typeName), value.ref > 0)
    }

    override fun computeChildren(node: XCompositeNode) {
        val container = value.children() ?: return node.addChildren(XValueChildrenList.EMPTY, true)
        loadChildren(engine, container, node, 0)
    }

    override fun getEvaluationExpression(): String = value.expression
}

/** runtime 给的底部分组（「继承自 BagPanel」「方法」「元表」）。[summary] 显示在分组名后面。 */
class LuaV3VariableGroup internal constructor(
    private val engine: DebugSession,
    private val container: VariableContainer,
    private val summary: String,
    private val ctx: ExpandContext = ExpandContext(null)
) : XValueGroup(container.name) {
    override fun getComment(): String? = summary.takeIf { it.isNotBlank() }

    override fun computeChildren(node: XCompositeNode) = loadChildren(engine, container, node, 0, ctx)
}

/** 作用域分组（upvalues / varargs / globals）。locals 直接平铺在帧下面。 */
class LuaV3ScopeGroup(
    private val engine: DebugSession,
    private val container: VariableContainer,
    private val frame: FrameHandle? = null
) : XValueGroup(container.name) {
    override fun isAutoExpand(): Boolean = false

    override fun computeChildren(node: XCompositeNode) = loadChildren(engine, container, node, 0, ExpandContext(frame))
}

/** IDE 里求值默认是任意 Lua；runtime 没有 luaEval 能力时退回取值路径。 */
class LuaV3Evaluator(private val engine: DebugSession, private val frame: FrameHandle) : XDebuggerEvaluator() {
    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        val mode = if (engine.hasCapability(Capabilities.LUA_EVAL)) EvalModes.LUA else EvalModes.VALUE_PATH
        val future: CompletableFuture<EvalValue> = engine.evaluate(frame.pause, expression, frame, mode, "watch")
        future.whenComplete { value, error ->
            if (error != null) callback.errorOccurred(messageOf(error)) else callback.evaluated(LuaV3EvalValue(engine, value))
        }
    }
}

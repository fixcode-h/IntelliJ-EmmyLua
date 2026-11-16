package com.tang.intellij.lua.debug.xdebugger

import com.intellij.icons.AllIcons
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XNamedValue
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

/** 一页取多少子项（协议 9.3：默认 100）。更多的用「显示更多」按需再取。 */
internal const val CHILDREN_PAGE = 100

/** 摘要直接用 runtime 给的 value，不在 IDE 侧拼（变量展示设计 15.2）。 */
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

/** 子项加载：分页、过期时静默显示提示（协议第 18 节：STALE 不弹错误）。 */
internal fun loadChildren(engine: DebugSession, container: VariableContainer, node: XCompositeNode, start: Int) {
    engine.variables(container, start, CHILDREN_PAGE).whenComplete { page, error ->
        if (node.isObsolete) return@whenComplete
        if (error != null) {
            node.setErrorMessage(messageOf(error))
            return@whenComplete
        }
        val list = XValueChildrenList()
        page.variables.forEach { list.add(LuaV3Value(engine, it.variable, container)) }
        val loaded = start + page.variables.size
        val remaining = page.total - loaded
        node.addChildren(list, remaining <= 0)
        if (remaining > 0) node.tooManyChildren(remaining) { loadChildren(engine, container, node, loaded) }
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
class LuaV3Value(
    private val engine: DebugSession,
    private val variable: VariableDto,
    private val parent: VariableContainer?
) : XNamedValue(variable.name) {
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(iconOf(variable.type), presentationOf(variable.type, variable.value, variable.typeName), variable.expandable)
    }

    override fun computeChildren(node: XCompositeNode) {
        val pause = parent?.pause ?: return node.addChildren(XValueChildrenList.EMPTY, true)
        if (!variable.expandable) return node.addChildren(XValueChildrenList.EMPTY, true)
        loadChildren(engine, VariableContainer(pause, variable.ref, variable.name), node, 0)
    }

    override fun getEvaluationExpression(): String? = variable.evaluateName

    override fun getModifier(): XValueModifier? {
        val container = parent ?: return null
        if (!engine.hasCapability(Capabilities.SET_VARIABLE)) return null
        return object : XValueModifier() {
            override fun setValue(expression: com.intellij.xdebugger.XExpression, callback: XModificationCallback) {
                engine.setVariable(container, variable.name, expression.expression, EvalModes.LUA).whenComplete { _, error ->
                    if (error == null) callback.valueModified() else callback.errorOccurred(messageOf(error))
                }
            }

            override fun getInitialValueEditorText(): String? = variable.value.takeIf { variable.type != "table" }
        }
    }

    private fun iconOf(type: String) = when (type) {
        "table" -> AllIcons.Json.Object
        "function" -> AllIcons.Nodes.Function
        else -> AllIcons.Debugger.Value
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

/** 作用域分组（upvalues / varargs / globals）。locals 直接平铺在帧下面。 */
class LuaV3ScopeGroup(private val engine: DebugSession, private val container: VariableContainer) : XValueGroup(container.name) {
    override fun isAutoExpand(): Boolean = false

    override fun computeChildren(node: XCompositeNode) = loadChildren(engine, container, node, 0)
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

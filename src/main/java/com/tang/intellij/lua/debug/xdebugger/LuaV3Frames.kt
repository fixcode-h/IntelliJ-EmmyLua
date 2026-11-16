package com.tang.intellij.lua.debug.xdebugger

import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import com.tang.intellij.lua.debug.engine.DebugSession
import com.tang.intellij.lua.debug.engine.FrameHandle
import com.tang.intellij.lua.debug.engine.PauseInfo
import com.tang.intellij.lua.debug.engine.VmView
import com.tang.intellij.lua.debug.protocol.ScopeNames
import com.tang.intellij.lua.debug.protocol.ThreadStatuses

/** 栈分页大小：协议上限 200，一般 50 帧以内就够看。 */
private const val STACK_PAGE = 50

/**
 * 一次暂停对应的挂起上下文。多个 VM 同时暂停时，每个暂停的 VM 一个执行栈；
 * runtime 支持协程时，每个 VM 里其他活着的协程也各是一个执行栈（「GameVM · 协程 …」），
 * 用户在线程下拉框里切换查看它们的栈和变量。
 */
class LuaV3SuspendContext(
    private val engine: DebugSession,
    val pause: PauseInfo
) : XSuspendContext() {
    private val active = LuaV3ExecutionStack(engine, pause, vmName(engine.vms(), pause.vmId))

    override fun getActiveExecutionStack(): XExecutionStack = active

    override fun getExecutionStacks(): Array<XExecutionStack> {
        val vms = engine.vms()
        val others = engine.pauses().filter { it.vmId != pause.vmId }.map { LuaV3ExecutionStack(engine, it, vmName(vms, it.vmId)) }
        return (listOf<XExecutionStack>(active) + others).toTypedArray()
    }

    /** 线程下拉框：先给各 VM 的暂停线程，再按 VM 异步补上协程（协程列表要问 runtime）。 */
    override fun computeExecutionStacks(container: XExecutionStackContainer) {
        val vms = engine.vms()
        val paused = listOf(pause) + engine.pauses().filter { it.vmId != pause.vmId }
        val main = getExecutionStacks().toList()
        if (paused.isEmpty()) return container.addExecutionStack(main, true)
        container.addExecutionStack(main, false)
        var remaining = paused.size
        paused.forEach { p ->
            engine.threads(p).whenComplete { threads, _ ->
                val coroutines = threads.orEmpty()
                    .filter { it.threadId != p.threadId && it.status != ThreadStatuses.DEAD && it.status != ThreadStatuses.RUNNING }
                    .map { t ->
                        val label = t.name ?: "协程 ${t.threadId.removePrefix("t")}"
                        LuaV3ExecutionStack(engine, p, "${vmName(vms, p.vmId)} · $label（${statusText(t.status)}）", t.threadId)
                    }
                val last = synchronized(this) { --remaining == 0 }
                if (!container.isObsolete) container.addExecutionStack(coroutines, last)
            }
        }
    }

    private fun statusText(status: String) = when (status) {
        ThreadStatuses.SUSPENDED -> "挂起"
        ThreadStatuses.NORMAL -> "等待子协程"
        else -> status
    }

    private fun vmName(vms: List<VmView>, vmId: Long) = vms.firstOrNull { it.vmId == vmId }?.name ?: "Lua VM #$vmId"
}

class LuaV3ExecutionStack(
    private val engine: DebugSession,
    val pause: PauseInfo,
    displayName: String,
    /** 为空表示暂停所在的线程；否则是同一 VM 里另一个协程。 */
    private val threadId: String? = null
) : XExecutionStack(displayName, if (threadId == null) AllIcons.Debugger.ThreadSuspended else AllIcons.Debugger.ThreadFrozen) {
    // 别的协程没有现成的栈顶帧，第一页取回来再说
    private val top: XStackFrame? = if (threadId == null) engine.topFrame(pause)?.let { LuaV3StackFrame(engine, it) } else null

    override fun getTopFrame(): XStackFrame? = top

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        loadFrames(firstFrameIndex, container)
    }

    private fun loadFrames(start: Int, container: XStackFrameContainer) {
        engine.stackTrace(pause, start, STACK_PAGE, threadId).whenComplete { page, error ->
            if (container.isObsolete) return@whenComplete
            if (error != null) {
                container.errorOccurred(messageOf(error))
                return@whenComplete
            }
            val frames = page.frames.map { handle ->
                // 栈顶帧复用 paused 事件里那个，避免同一帧两个对象导致选中状态跳动
                if (handle.frame.level == 0 && top != null) top else LuaV3StackFrame(engine, handle)
            }
            val loaded = start + page.frames.size
            val last = loaded >= page.total || page.frames.isEmpty()
            container.addStackFrames(frames, last)
            if (!last) loadFrames(loaded, container)
        }
    }
}

class LuaV3StackFrame(private val engine: DebugSession, val handle: FrameHandle) : XStackFrame() {
    private val position: XSourcePosition? by lazy {
        val path = handle.localPath ?: return@lazy null
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return@lazy null
        XDebuggerUtil.getInstance().createPosition(file, handle.frame.line - 1)
    }

    override fun getSourcePosition(): XSourcePosition? = position

    /**
     * 跨暂停稳定的帧身份：同一个函数（VM + 协程 + chunk + 定义行 + 名字）。frameId 每次暂停都换新，
     * 用它的话 IntelliJ 认不出「还是这个帧」，单步后既不恢复展开状态，也不会把变了的值标成高亮。
     */
    override fun getEqualityObject(): Any = FrameIdentity(
        handle.pause.vmId, handle.frame.threadId ?: handle.pause.threadId, handle.frame.chunkName,
        handle.frame.lineDefined ?: -1, handle.frame.name
    )

    override fun getEvaluator(): XDebuggerEvaluator = LuaV3Evaluator(engine, handle)

    override fun customizePresentation(component: ColoredTextContainer) {
        val frame = handle.frame
        component.append(frame.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        val location = when {
            !frame.hasSource -> "[C]"
            else -> "${frame.chunkName.removePrefix("@")}:${frame.line}"
        }
        component.append("  $location", if (position == null) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.GRAY_ATTRIBUTES)
        component.setIcon(AllIcons.Debugger.Frame)
    }

    override fun computeChildren(node: XCompositeNode) {
        engine.scopes(handle).whenComplete { scopes, error ->
            if (node.isObsolete) return@whenComplete
            if (error != null) {
                node.setErrorMessage(messageOf(error))
                return@whenComplete
            }
            val locals = scopes.firstOrNull { it.name == ScopeNames.LOCALS }
            val groups = XValueChildrenList()
            scopes.filter { it !== locals }.forEach { groups.addBottomGroup(LuaV3ScopeGroup(engine, it, handle)) }
            if (locals == null) {
                node.addChildren(groups, true)
            } else {
                // 局部变量直接平铺在帧下面（和旧版一致），其余作用域放在底部分组
                node.addChildren(groups, false)
                loadChildren(engine, locals, node, 0, ExpandContext(handle))
            }
        }
    }
}

/** [LuaV3StackFrame.getEqualityObject]：平台按它保存 / 恢复变量树状态，并对比新旧值标出变化（variable-view.md 16）。 */
private data class FrameIdentity(val vmId: Long, val threadId: String?, val chunkName: String, val lineDefined: Int, val name: String)

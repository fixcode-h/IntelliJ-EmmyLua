package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import com.fixcode.intellij.lua.debug.engine.DebugSession
import com.fixcode.intellij.lua.debug.engine.FrameHandle
import com.fixcode.intellij.lua.debug.engine.VariableHandle
import com.fixcode.intellij.lua.debug.protocol.ScopeNames
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture

data class DebugValueSnapshot(
    val name: String,
    val value: String?,
    val type: String?,
    val children: Map<String, DebugValueSnapshot> = emptyMap()
) {
    val isContainer: Boolean
        get() = children.isNotEmpty() || type.equals("table", true) || type.equals("userdata", true)
}

data class DebugFrameSnapshot(
    val filePath: String,
    val line: Int,
    val variables: Map<String, DebugValueSnapshot>
)

/**
 * 编辑器行尾显示变量值用的快照：暂停或切换栈帧时一次取好（局部变量、upvalue 和它们下一层成员），
 * [LuaInlineValuePainter] 绘制时只读这里，不在绘制线程里请求 runtime。
 */
@Service(Service.Level.PROJECT)
class LuaInlineValueStore {
    private val snapshots = Collections.synchronizedMap(WeakHashMap<XDebugSession, DebugFrameSnapshot>())
    /** 每个会话最近一次请求的帧；旧请求晚到时丢掉，避免快速切帧时显示成别的帧的值。 */
    private val latest = Collections.synchronizedMap(WeakHashMap<XDebugSession, Long>())

    fun get(session: XDebugSession, file: VirtualFile): DebugFrameSnapshot? {
        val snapshot = snapshots[session] ?: return null
        return snapshot.takeIf { debugPathsEqual(it.filePath, file.canonicalPath ?: file.path) }
    }

    /** 为 [frame] 取快照；没有本地文件的帧（C 函数、没映射上）直接清掉。 */
    fun load(session: XDebugSession, engine: DebugSession, frame: FrameHandle) {
        val path = frame.localPath
        if (path == null) return clear(session)
        latest[session] = frame.frameId
        collect(engine, frame).whenComplete { variables, error ->
            if (latest[session] != frame.frameId) return@whenComplete
            if (error != null || variables == null) {
                snapshots.remove(session)
            } else {
                snapshots[session] = DebugFrameSnapshot(path, frame.frame.line - 1, variables)
            }
            repaint(session.project)
        }
    }

    fun clear(session: XDebugSession) {
        latest.remove(session)
        if (snapshots.remove(session) != null) repaint(session.project)
    }

    private fun collect(engine: DebugSession, frame: FrameHandle): CompletableFuture<Map<String, DebugValueSnapshot>> =
        engine.scopes(frame).thenCompose { scopes ->
            // upvalue 先放、局部变量后放：同名时局部变量覆盖（和 Lua 的可见性一致）
            val ordered = listOfNotNull(
                scopes.firstOrNull { it.name == ScopeNames.UPVALUES },
                scopes.firstOrNull { it.name == ScopeNames.LOCALS }
            )
            val pages = ordered.map { engine.variables(it, 0, TOP_LIMIT) }
            CompletableFuture.allOf(*pages.toTypedArray()).thenCompose {
                val top = pages.flatMap { it.join().variables }
                var budget = CHILD_TABLES
                val futures = top.map { handle ->
                    val depth = if (handle.variable.name == "self") 2 else 1
                    snapshotOf(engine, handle, depth) { budget-- > 0 }
                }
                CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
                    val result = linkedMapOf<String, DebugValueSnapshot>()
                    futures.forEach { f -> f.join().let { result[it.name] = it } }
                    result as Map<String, DebugValueSnapshot>
                }
            }
        }

    /** [depth] 层以内、预算允许时展开 table 的成员；取不到成员时只保留自身的值。 */
    private fun snapshotOf(
        engine: DebugSession,
        handle: VariableHandle,
        depth: Int,
        takeBudget: () -> Boolean
    ): CompletableFuture<DebugValueSnapshot> {
        val v = handle.variable
        val self = DebugValueSnapshot(v.name, v.value, v.type)
        val container = handle.children()
        if (depth <= 0 || container == null || !takeBudget()) return CompletableFuture.completedFuture(self)
        return engine.variables(container, 0, CHILD_LIMIT).thenCompose { page ->
            val children = page.variables.map { snapshotOf(engine, it, depth - 1, takeBudget) }
            CompletableFuture.allOf(*children.toTypedArray()).thenApply {
                self.copy(children = children.associate { f -> f.join().let { it.name to it } })
            }
        }.exceptionally { self }
    }

    private fun repaint(project: Project) {
        ApplicationManager.getApplication().invokeLater({
            EditorFactory.getInstance().allEditors
                .filter { it.project == project || it.project == null }
                .filter { FileDocumentManager.getInstance().getFile(it.document) != null }
                .forEach { it.contentComponent.repaint() }
        }, project.disposed)
    }

    companion object {
        private const val TOP_LIMIT = 200
        private const val CHILD_LIMIT = 100
        /** 一次暂停最多展开多少个 table，避免变量很多时每次暂停都发一堆请求。 */
        private const val CHILD_TABLES = 40

        @JvmStatic
        fun getInstance(project: Project): LuaInlineValueStore = project.getService(LuaInlineValueStore::class.java)
    }
}

internal fun debugPathsEqual(
    left: String,
    right: String,
    caseSensitive: Boolean = SystemInfoRt.isFileSystemCaseSensitive
): Boolean {
    val normalizedLeft = left.replace('\\', '/').trimEnd('/')
    val normalizedRight = right.replace('\\', '/').trimEnd('/')
    return if (caseSensitive) normalizedLeft == normalizedRight else normalizedLeft.equals(normalizedRight, ignoreCase = true)
}

package com.tang.intellij.lua.debugger

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import java.util.Collections
import java.util.WeakHashMap

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

@Service(Service.Level.PROJECT)
class InlineDebugSnapshotStore {
    private val snapshots = Collections.synchronizedMap(WeakHashMap<XDebugSession, DebugFrameSnapshot>())

    fun update(session: XDebugSession, snapshot: DebugFrameSnapshot) {
        snapshots[session] = snapshot
    }

    fun get(session: XDebugSession, file: VirtualFile): DebugFrameSnapshot? {
        val snapshot = snapshots[session] ?: return null
        return snapshot.takeIf { debugPathsEqual(it.filePath, file.canonicalPath ?: file.path) }
    }

    fun clear(session: XDebugSession) {
        snapshots.remove(session)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): InlineDebugSnapshotStore =
            project.getService(InlineDebugSnapshotStore::class.java)
    }
}

internal fun debugPathsEqual(
    left: String,
    right: String,
    caseSensitive: Boolean = SystemInfoRt.isFileSystemCaseSensitive
): Boolean {
    val normalizedLeft = left.replace('\\', '/').trimEnd('/')
    val normalizedRight = right.replace('\\', '/').trimEnd('/')
    return if (caseSensitive) {
        normalizedLeft == normalizedRight
    } else {
        normalizedLeft.equals(normalizedRight, ignoreCase = true)
    }
}

package com.tang.intellij.lua.debugger

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.tang.intellij.lua.debugger.core.normalizeDebuggerPath
import com.tang.intellij.lua.psi.LuaFileUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SourceMappingService(private val project: Project) {
    private val resolvedFiles = ConcurrentHashMap<String, VirtualFile>()
    @Volatile private var rootsModificationCount = Long.MIN_VALUE

    fun createPosition(filePath: String, oneBasedLine: Int): XSourcePosition? {
        if (oneBasedLine <= 0) return null
        val file = resolveFile(filePath) ?: return null
        return XDebuggerUtil.getInstance().createPosition(file, oneBasedLine - 1)
    }

    fun resolveFile(filePath: String): VirtualFile? {
        invalidateIfRootsChanged()
        val normalized = normalizeDebuggerPath(filePath)
        resolvedFiles[normalized]?.let { cached ->
            if (cached.isValid) return cached
            resolvedFiles.remove(normalized, cached)
        }

        val resolved = ReadAction.compute<VirtualFile?, RuntimeException> {
            resolveUnderReadAction(normalized)
        }
        if (resolved != null) resolvedFiles[normalized] = resolved
        return resolved
    }

    fun invalidate() {
        resolvedFiles.clear()
        rootsModificationCount = ProjectRootManager.getInstance(project).modificationCount
    }

    fun displayPath(filePath: String): String {
        val normalized = normalizeDebuggerPath(filePath)
        val base = project.basePath?.let(::normalizeDebuggerPath)?.trimEnd('/') ?: return File(normalized).name
        return if (normalized.startsWith("$base/", ignoreCase = true)) {
            normalized.substring(base.length + 1)
        } else {
            File(normalized).name
        }
    }

    private fun resolveUnderReadAction(filePath: String): VirtualFile? {
        val localFileSystem = LocalFileSystem.getInstance()
        localFileSystem.findFileByPath(filePath)?.let { return it }

        if (!isAbsolutePath(filePath)) {
            project.basePath?.let { basePath ->
                localFileSystem.findFileByPath("${normalizeDebuggerPath(basePath).trimEnd('/')}/$filePath")
                    ?.let { return it }
            }
        }

        LuaFileUtil.findFile(project, filePath)?.let { return it }
        val fileName = File(filePath).name.substringBeforeLast('.')
        return if (fileName.isBlank()) null else LuaFileUtil.findFile(project, fileName)
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith('/') || path.startsWith("//") || (path.length >= 3 && path[1] == ':' && path[2] == '/')

    private fun invalidateIfRootsChanged() {
        val current = ProjectRootManager.getInstance(project).modificationCount
        if (current == rootsModificationCount) return
        synchronized(resolvedFiles) {
            if (current != rootsModificationCount) {
                resolvedFiles.clear()
                rootsModificationCount = current
            }
        }
    }

    companion object {
        fun getInstance(project: Project): SourceMappingService = project.service()
    }
}

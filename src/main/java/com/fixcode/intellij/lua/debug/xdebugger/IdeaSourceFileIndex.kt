package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.fixcode.intellij.lua.debug.engine.SourceFileIndex
import com.fixcode.intellij.lua.debug.engine.SourcePaths
import java.io.File

/**
 * 引擎的文件查找接到 IntelliJ：用文件名索引找候选，再按路径段后缀过滤。
 * 引擎在会话线程（后台）调用，这里自己进读动作。
 */
class IdeaSourceFileIndex(private val project: Project) : SourceFileIndex {
    override fun exists(path: String): Boolean =
        LocalFileSystem.getInstance().findFileByPath(path)?.isDirectory == false || File(path).isFile

    override fun findBySuffix(segments: List<String>, caseSensitive: Boolean): List<String> {
        val baseName = segments.lastOrNull() ?: return emptyList()
        if (project.isDisposed) return emptyList()
        return ReadAction.compute<List<String>, RuntimeException> {
            val scope = GlobalSearchScope.allScope(project)
            EXTENSIONS.flatMap { ext ->
                FilenameIndex.getVirtualFilesByName("$baseName$ext", !caseSensitive, scope)
            }.map { it.path }
                .filter { SourcePaths.segments(it, caseSensitive).takeLast(segments.size) == segments }
                .distinct()
        }
    }

    override fun sha256(path: String): String? = SourceFileIndex.sha256Of(File(path))

    private companion object {
        val EXTENSIONS = listOf(".lua", ".luac", ".txt", ".bytes")
    }
}

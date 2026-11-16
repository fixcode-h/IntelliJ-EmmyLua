package com.fixcode.intellij.lua.debug.ue

import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import java.nio.file.Path

private fun VirtualFile.isLocalLua() = !isDirectory && isInLocalFileSystem && extension.equals("lua", ignoreCase = true)

/** 只对 UnLua 脚本目录里的文件夹显示，别的目录右键菜单里不出现。 */
private fun VirtualFile.isScriptDir() = isDirectory && isInLocalFileSystem &&
    ("$path/").contains("/Content/Script/", ignoreCase = true)

/**
 * 「重载到 UE」：编辑器右键、项目视图右键（可多选、可选目录）和快捷键。
 * 先保存这些文件，再经 UELink 让开着的 UE 编辑器 / 游戏重新加载对应模块。
 */
class ReloadToUnrealAction : DumbAwareAction(
    LuaDebugBundle.message("action.reloadToUnreal"),
    LuaDebugBundle.message("action.reloadToUnreal.description"),
    null,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
            ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        e.presentation.isEnabledAndVisible = e.project != null &&
            files.any { it.isLocalLua() || it.isScriptDir() }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
            ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        val files = collectLua(selected)
        if (files.isEmpty()) return
        // UE 读的是磁盘上的文件：没保存的改动先落盘
        val fdm = FileDocumentManager.getInstance()
        files.forEach { f -> fdm.getCachedDocument(f)?.let { if (fdm.isDocumentUnsaved(it)) fdm.saveDocument(it) } }
        UnrealLuaReloader.getInstance(project).reloadFilesInBackground(files.map { it.toNioPath() })
    }

    /** 选中目录时收集里面的 .lua（最多 [MAX_FILES] 个，防止误选整个 Content）。 */
    private fun collectLua(selected: List<VirtualFile>): List<VirtualFile> {
        val result = LinkedHashSet<VirtualFile>()
        for (file in selected) {
            if (file.isLocalLua()) { result += file; continue }
            if (!file.isScriptDir()) continue
            VfsUtilCore.visitChildrenRecursively(file, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(f: VirtualFile): Boolean {
                    if (result.size >= MAX_FILES) return false
                    if (f.isLocalLua()) result += f
                    return true
                }
            })
        }
        return result.toList()
    }

    private companion object {
        const val MAX_FILES = 500
    }
}

/**
 * 设置里打开「保存 Lua 文件后自动重载到 UE」时，每次保存把文件交给 [UnrealLuaReloader]：
 * 它攒 300 毫秒再一起发，所以「全部保存」只调一次 UELink。
 * beforeDocumentSaving 时文件还没写盘，防抖的这段时间足够写完。
 */
class UnrealReloadOnSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        if (!UnrealLuaReloader.autoReloadEnabled()) return
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isLocalLua()) return
        val path: Path = runCatching { file.toNioPath() }.getOrNull() ?: return
        for (project in ProjectLocator.getInstance().getProjectsForFile(file)) {
            if (project == null || project.isDisposed || project.isDefault) continue
            UnrealLuaReloader.getInstance(project).scheduleOnSave(path)
        }
    }
}

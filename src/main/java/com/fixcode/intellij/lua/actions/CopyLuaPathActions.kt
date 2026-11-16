package com.fixcode.intellij.lua.actions

import com.fixcode.intellij.lua.LuaBundle
import com.fixcode.intellij.lua.lang.LuaFileType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.UIUtil
import java.awt.datatransfer.StringSelection

/** 「复制路径/引用」菜单里的两项：`A.B.C`，以及 `local C = require("A.B.C")`。 */
abstract class CopyLuaPathAction(private val messageKey: String) : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    protected abstract fun format(file: VirtualFile, requirePath: String): String

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = luaFile(e) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(format(file, requirePath(project, file))))
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = luaFile(e)
        e.presentation.isEnabledAndVisible = project != null && file != null
        if (project == null || file == null) return
        val gray = UIUtil.getInactiveTextColor()
        val hex = String.format("#%02x%02x%02x", gray.red, gray.green, gray.blue)
        e.presentation.text = "<html>${LuaBundle.message(messageKey)} <font color='$hex'>${format(file, requirePath(project, file))}</font></html>"
    }

    private fun luaFile(e: AnActionEvent): VirtualFile? {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.firstOrNull()
            ?: e.getData(CommonDataKeys.EDITOR)?.let { FileDocumentManager.getInstance().getFile(it.document) }
            ?: return null
        return file.takeIf { !it.isDirectory && (it.fileType == LuaFileType || it.extension.equals("lua", true)) }
    }

    companion object {
        /** 相对内容根（IDE 打开的目录，UE 项目里一般就是 Content/Script）的模块路径。 */
        fun requirePath(project: Project, file: VirtualFile): String {
            val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
            val relative = root?.let { VfsUtilCore.getRelativePath(file, it, '/') } ?: file.name
            return relative.removeSuffix(".lua").removeSuffix(".txt").removeSuffix(".lua").replace('/', '.')
        }
    }
}

class CopyLuaRelativePathAction : CopyLuaPathAction("action.copy_lua_relative_path") {
    override fun format(file: VirtualFile, requirePath: String) = requirePath
}

class CopyLuaRequirePathAction : CopyLuaPathAction("action.copy_lua_require_path") {
    override fun format(file: VirtualFile, requirePath: String) =
        "local ${file.nameWithoutExtension.removeSuffix(".lua")} = require(\"$requirePath\")"
}

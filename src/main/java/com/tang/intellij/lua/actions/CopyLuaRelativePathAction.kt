/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.ui.UIUtil
import com.tang.intellij.lua.LuaBundle
import com.tang.intellij.lua.lang.LuaFileType
import java.awt.datatransfer.StringSelection

class CopyLuaRelativePathAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    private fun getVirtualFile(e: AnActionEvent): VirtualFile? {
        // 首先尝试从项目视图获取文件
        var file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (file != null) return file
        
        // 尝试从文件数组获取
        file = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.firstOrNull()
        if (file != null) return file
        
        // 尝试从编辑器获取当前文件
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            val document = editor.document
            file = FileDocumentManager.getInstance().getFile(document)
            if (file != null) return file
        }
        
        return null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = getVirtualFile(e) ?: return
        
        val convertedPath = generateRelativePath(file, project)
        
        if (convertedPath != null) {
            // 复制到剪贴板
            val selection = StringSelection(convertedPath)
            CopyPasteManager.getInstance().setContents(selection)
        }
    }

    override fun update(e: AnActionEvent) {
        val file = getVirtualFile(e)
        val isLuaFile = file != null && !file.isDirectory && 
                       (file.fileType is LuaFileType || file.extension?.lowercase() == "lua")
        
        if (isLuaFile && file != null) {
            val project = e.project
            if (project != null) {
                val relativePath = generateRelativePath(file, project)
                if (relativePath != null) {
                    // 获取当前主题下的灰色
                    val grayColor = UIUtil.getInactiveTextColor()
                    val hexColor = String.format("#%02x%02x%02x", 
                        grayColor.red, grayColor.green, grayColor.blue)
                    
                    // 使用HTML来显示主文本和灰色预览文本
                    e.presentation.text = "<html>${LuaBundle.message("action.copy_lua_relative_path")} <font color='$hexColor'>$relativePath</font></html>"
                } else {
                    e.presentation.text = LuaBundle.message("action.copy_lua_relative_path")
                }
            } else {
                e.presentation.text = LuaBundle.message("action.copy_lua_relative_path")
            }
        }
        
        e.presentation.isEnabledAndVisible = isLuaFile
    }
    
    /**
     * Generate relative path for preview
     */
    private fun generateRelativePath(file: VirtualFile, project: com.intellij.openapi.project.Project): String? {
        val projectBasePath = project.basePath ?: return null
        val filePath = file.path
        
        // 计算相对路径
        val relativePath = if (filePath.startsWith(projectBasePath)) {
            filePath.substring(projectBasePath.length + 1)
        } else {
            file.name
        }
        
        // 转换路径：替换 / 为 .，移除 .lua 后缀
        return relativePath
            .replace('\\', '.')
            .replace('/', '.')
            .removeSuffix(".lua")
    }
}
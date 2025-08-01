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

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.ui.UIUtil
import com.tang.intellij.lua.LuaBundle
import com.tang.intellij.lua.lang.LuaFileType
import com.tang.intellij.lua.project.LuaSettings
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Action to copy Lua require path to clipboard
 */
class CopyLuaRequirePathAction : AnAction() {

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
        val virtualFile = getVirtualFile(e) ?: return
        val project = e.project ?: return
        
        val requireStatement = generateRequireStatement(virtualFile, project)
        
        if (requireStatement != null) {
            val copyPasteManager = CopyPasteManager.getInstance()
            copyPasteManager.setContents(StringSelection(requireStatement))
        }
    }

    override fun update(e: AnActionEvent) {
        val virtualFile = getVirtualFile(e)
        val isLuaFile = virtualFile != null && !virtualFile.isDirectory && 
                       (virtualFile.fileType is LuaFileType || virtualFile.extension?.lowercase() == "lua")
        
        if (isLuaFile && virtualFile != null) {
            val project = e.project
            if (project != null) {
                val requireStatement = generateRequireStatement(virtualFile, project)
                if (requireStatement != null) {
                    // 获取当前主题下的灰色
                    val grayColor = UIUtil.getInactiveTextColor()
                    val hexColor = String.format("#%02x%02x%02x", 
                        grayColor.red, grayColor.green, grayColor.blue)
                    
                    // 使用HTML来显示主文本和灰色预览文本
                    e.presentation.text = "<html>${LuaBundle.message("action.copy_lua_require_path")} <font color='$hexColor'>$requireStatement</font></html>"
                } else {
                    e.presentation.text = LuaBundle.message("action.copy_lua_require_path")
                }
            } else {
                e.presentation.text = LuaBundle.message("action.copy_lua_require_path")
            }
        }
        
        e.presentation.isEnabledAndVisible = isLuaFile
    }
    
    /**
     * Generate complete require statement: local fileName = require("path")
     */
    private fun generateRequireStatement(virtualFile: VirtualFile, project: com.intellij.openapi.project.Project): String? {
        // Get require path
        var requirePath: String? = null
        
        try {
            // Attempt to use existing utility
            requirePath = com.tang.intellij.lua.psi.LuaFileUtil.asRequirePath(project, virtualFile)
        } catch (ex: Exception) {
            // Ignore exception and use fallback
        }
        
        // If asRequirePath returns null or throws exception, use fallback
        if (requirePath == null) {
            requirePath = convertToRequirePath(virtualFile.path, project)
        }
        
        if (requirePath != null) {
            val fileName = virtualFile.nameWithoutExtension
            return "local $fileName = require(\"$requirePath\")"
        }
        
        return null
    }

    /**
     * Manual conversion of file path to require path
     */
    private fun convertToRequirePath(filePath: String, project: com.intellij.openapi.project.Project): String? {
        try {
            val file = File(filePath)
            val fileName = file.nameWithoutExtension
            
            // Get project base path
            val projectBasePath = project.basePath ?: return fileName
            val projectDir = File(projectBasePath)
            
            // Get relative path from project root
            val relativePath = projectDir.toURI().relativize(file.toURI()).path
            
            // Convert to require format: replace slashes with dots and remove .lua extension
            return relativePath
                .replace('/', '.')
                .replace('\\', '.')
                .removeSuffix(".lua")
        } catch (e: Exception) {
            return null
        }
    }
}
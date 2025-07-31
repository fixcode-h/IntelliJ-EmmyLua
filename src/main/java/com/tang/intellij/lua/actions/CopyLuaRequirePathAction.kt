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
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.tang.intellij.lua.lang.LuaFileType
import com.tang.intellij.lua.project.LuaSettings
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Action to copy Lua require path to clipboard
 */
class CopyLuaRequirePathAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val project = e.project ?: return
        
        // Try to use LuaFileUtil if available, otherwise use manual conversion
        val requirePath = try {
            // Attempt to use existing utility
            com.tang.intellij.lua.psi.LuaFileUtil.asRequirePath(project, virtualFile.path)
        } catch (ex: Exception) {
            // Fallback to manual conversion
            convertToRequirePath(virtualFile.path, project)
        }
        
        if (requirePath != null) {
            val copyPasteManager = CopyPasteManager.getInstance()
            copyPasteManager.setContents(StringSelection(requirePath))
        }
    }

    override fun update(e: AnActionEvent) {
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val isLuaFile = virtualFile?.fileType is LuaFileType
        e.presentation.isEnabledAndVisible = isLuaFile
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
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

package com.tang.intellij.lua.refactoring.extract

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.tang.intellij.lua.LuaBundle
import com.tang.intellij.lua.lang.LuaFileType

/**
 * 提取函数的Action
 * 在编辑器右键菜单和重构菜单中提供"提取函数"选项
 */
class LuaExtractMethodAction : AnAction(
    LuaBundle.message("refactor.extract_method.action_name"),
    LuaBundle.message("refactor.extract_method.action_description"),
    null
) {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(LangDataKeys.PSI_FILE) ?: return

        val handler = LuaExtractMethodHandler()
        handler.invoke(project, editor, psiFile, e.dataContext)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(LangDataKeys.PSI_FILE)

        val isLuaFile = psiFile?.fileType == LuaFileType.INSTANCE
        val hasSelection = editor?.selectionModel?.hasSelection() == true

        // 调试信息
        val shouldShow = project != null && isLuaFile && hasSelection
        
        // 如果是Lua文件但没有选中文本，仍然显示但禁用按钮
        val isVisible = project != null && isLuaFile
        val isEnabled = shouldShow
        
        e.presentation.isVisible = isVisible
        e.presentation.isEnabled = isEnabled
        
        // 设置提示文本
        if (isVisible && !isEnabled) {
            e.presentation.description = LuaBundle.message("refactor.extract_method.validation.no_selection")
        } else {
            e.presentation.description = LuaBundle.message("refactor.extract_method.action_description")
        }
    }
}
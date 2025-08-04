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

package com.tang.intellij.lua.codeInsight.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import com.tang.intellij.lua.LuaBundle
import com.tang.intellij.lua.lang.LuaFileType
import com.tang.intellij.lua.refactoring.extract.LuaExtractMethodHandler
import com.tang.intellij.lua.refactoring.extract.LuaExtractMethodOperation

/**
 * 提取函数的 Intention Action
 * 在编辑器的上下文操作中提供"提取函数"选项
 */
class ExtractMethodIntention : IntentionAction {

    override fun getText(): String {
        return LuaBundle.message("refactor.extract_method.action_name")
    }

    override fun getFamilyName(): String {
        return LuaBundle.message("refactor.extract_method.action_name")
    }

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        // 只在 Lua 文件中且有选中文本时可用
        return file.fileType == LuaFileType.INSTANCE && 
               editor.selectionModel.hasSelection()
    }

    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            return
        }

        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        val startElement = file.findElementAt(startOffset) ?: return
        val endElement = file.findElementAt(endOffset - 1) ?: return

        val operation = LuaExtractMethodOperation(
            project, editor, file, startElement, endElement, startOffset, endOffset
        )

        if (operation.isValidSelection()) {
            operation.performExtraction()
        }
    }

    override fun startInWriteAction(): Boolean {
        return false
    }
}
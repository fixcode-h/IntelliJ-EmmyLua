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

package com.tang.intellij.lua.debugger.luapanda

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.tang.intellij.lua.lang.LuaFileType

/**
 * LuaPanda调试器编辑器提供器
 * 提供Lua代码片段的编辑支持
 */
class LuaPandaDebuggerEditorsProvider : XDebuggerEditorsProvider() {

    override fun getFileType(): FileType {
        return LuaFileType.INSTANCE
    }

    override fun createDocument(
        project: Project, 
        text: String, 
        sourcePosition: com.intellij.xdebugger.XSourcePosition?, 
        mode: EvaluationMode
    ): Document {
        return EditorFactory.getInstance().createDocument(text)
    }
} 
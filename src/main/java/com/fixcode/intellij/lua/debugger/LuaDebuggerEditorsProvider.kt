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

package com.fixcode.intellij.lua.debugger

import com.fixcode.intellij.lua.lang.LuaFileType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase

/**
 * 求值 / 监视 / 条件断点输入框。
 *
 * 语言支持迁到 LSP 后没有表达式级的 PSI 了，输入框就是一个 Lua 文件类型的普通代码片段（扁平 PSI），
 * 高亮、括号匹配照常由词法器提供；补全等语义不在这里做。
 * [isPhysical] 为 true 时（基类建输入框文档时就是这样传的）打开事件系统，文档和 PSI 才会同步，输入时高亮跟着更新。
 *
 * Created by TangZX on 2016/12/30.
 */
class LuaDebuggerEditorsProvider : XDebuggerEditorsProviderBase() {
    override fun createExpressionCodeFragment(project: Project, text: String, context: PsiElement?, isPhysical: Boolean): PsiFile =
        PsiFileFactory.getInstance(project)
            .createFileFromText("fragment.lua", LuaFileType, text, LocalTimeCounter.currentTime(), isPhysical)

    override fun getFileType(): FileType = LuaFileType
}

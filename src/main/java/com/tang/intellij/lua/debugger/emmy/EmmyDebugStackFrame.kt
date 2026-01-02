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

package com.tang.intellij.lua.debugger.emmy

import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.SlowOperations
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.impl.XSourcePositionImpl
import com.tang.intellij.lua.debugger.emmy.value.LuaXValue
import com.tang.intellij.lua.psi.LuaFileUtil

class EmmyDebugStackFrame(val data: Stack, val process: EmmyDebugProcessBase) : XStackFrame() {
    private val values = XValueChildrenList()
    private var evaluator: EmmyEvaluator? = null
    
    // 缓存 sourcePosition，避免重复计算
    private var _sourcePosition: XSourcePosition? = null
    private var _sourcePositionComputed = false

    init {
        data.localVariables.forEach {
            addValue(LuaXValue.create(it, this))
        }
        data.upvalueVariables.forEach {
            addValue(LuaXValue.create(it, this))
        }
    }

    override fun getEvaluator(): EmmyEvaluator? {
        if (evaluator == null)
            evaluator = EmmyEvaluator(this, process)
        return evaluator
    }

    override fun customizePresentation(component: ColoredTextContainer) {
        val relativePath = getRelativePath(data.file)
        component.append("${relativePath}:${data.functionName}:${data.line}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    private fun getRelativePath(filePath: String): String {
        val projectBasePath = process.session.project.basePath
        return if (projectBasePath != null && filePath.startsWith(projectBasePath)) {
            filePath.substring(projectBasePath.length + 1).replace('\\', '/')
        } else {
            // 如果不在项目目录下，尝试获取文件名
            val file = java.io.File(filePath)
            file.name
        }
    }

    private fun addValue(node: LuaXValue) {
        values.add(node.name, node)
    }

    override fun computeChildren(node: XCompositeNode) {
        node.addChildren(values, true)
    }

    override fun getSourcePosition(): XSourcePosition? {
        if (!_sourcePositionComputed) {
            // 使用 knownIssue 标记这是一个已知的 EDT 慢操作问题
            // getSourcePosition 由 XDebuggerFramesList 在 EDT 上调用，无法避免
            SlowOperations.knownIssue("EMMYLUA-EDT-StackFrame").use {
                val file = LuaFileUtil.findFile(process.session.project, data.file)
                _sourcePosition = if (file == null) null else XSourcePositionImpl.create(file, data.line - 1)
            }
            _sourcePositionComputed = true
        }
        return _sourcePosition
    }
}
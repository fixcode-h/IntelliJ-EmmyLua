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

import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.XSourcePosition
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.tang.intellij.lua.debugger.LuaDebuggerEditorsProvider

class LuaPandaSuspendContext(
    private val debugProcess: LuaPandaDebugProcess,
    private val stacks: List<LuaPandaStack>
) : XSuspendContext() {

    override fun getActiveExecutionStack(): XExecutionStack? {
        return if (stacks.isNotEmpty()) {
            LuaPandaExecutionStack(debugProcess, stacks)
        } else null
    }

    override fun getExecutionStacks(): Array<XExecutionStack> {
        return arrayOf(LuaPandaExecutionStack(debugProcess, stacks))
    }
}

class LuaPandaExecutionStack(
    private val debugProcess: LuaPandaDebugProcess,
    private val stacks: List<LuaPandaStack>
) : XExecutionStack("LuaPanda") {

    override fun getTopFrame(): XStackFrame? {
        return if (stacks.isNotEmpty()) {
            LuaPandaStackFrame(debugProcess, stacks[0])
        } else null
    }

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer?) {
        val frames = stacks.drop(firstFrameIndex).map { stack ->
            LuaPandaStackFrame(debugProcess, stack)
        }
        container?.addStackFrames(frames, true)
    }
}

class LuaPandaStackFrame(
    private val debugProcess: LuaPandaDebugProcess,
    private val stack: LuaPandaStack
) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? {
        val file = LocalFileSystem.getInstance().findFileByPath(stack.file)
        return if (file != null && stack.line > 0) {
            XDebuggerUtil.getInstance().createPosition(file, stack.line - 1) // Convert to 0-based
        } else null
    }

    override fun computeChildren(node: XCompositeNode) {
        val children = XValueChildrenList()
        
        // 添加局部变量
        stack.locals?.forEach { variable ->
            children.add(variable.name, LuaPandaValue(debugProcess, variable))
        }
        
        // 添加上值
        stack.upvalues?.forEach { variable ->
            children.add(variable.name, LuaPandaValue(debugProcess, variable))
        }
        
        node.addChildren(children, true)
    }

    override fun customizePresentation(component: ColoredTextContainer) {
        component.append(stack.functionName ?: "unknown", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        component.append(" (${stack.file}:${stack.line})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
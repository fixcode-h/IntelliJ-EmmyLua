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
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.XSourcePosition
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.tang.intellij.lua.debugger.LuaDebuggerEditorsProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import javax.swing.Icon

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
    private val stack: LuaPandaStack,
    private val stackId: Int = stack.getIndex()
) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? {
        // 优先使用oPath（完整路径），如果没有则使用file字段
        val filePath = stack.oPath ?: stack.file
        val file = LocalFileSystem.getInstance().findFileByPath(filePath)
        val lineNumber = stack.getLineNumber()
        return if (file != null && lineNumber > 0) {
            XDebuggerUtil.getInstance().createPosition(file, lineNumber - 1) // Convert to 0-based
        } else null
    }

    override fun computeChildren(node: XCompositeNode) {
        // 如果已经有变量信息，直接使用
        if (stack.locals != null || stack.upvalues != null) {
            val children = XValueChildrenList()
            
            // 添加局部变量
            stack.locals?.forEach { variable ->
                children.add(variable.name, LuaPandaValue(debugProcess, variable, stackId))
            }
            
            // 添加上值
            stack.upvalues?.forEach { variable ->
                children.add(variable.name, LuaPandaValue(debugProcess, variable, stackId))
            }
            
            node.addChildren(children, true)
        } else {
            // 主动发送getVariable请求获取栈帧变量
            val info = JsonObject().apply {
                addProperty("varRef", "10000")  // 10000表示获取栈帧的局部变量
                addProperty("stackId", stackId.toString())
            }
            
            debugProcess.transporter?.commandToDebugger(LuaPandaCommands.GET_VARIABLE, info, { response ->
                try {
                    val children = XValueChildrenList()
                    
                    // 解析返回的变量信息 - info字段直接是变量数组
                    if (response.info?.isJsonArray == true) {
                        response.info.asJsonArray.forEach { element ->
                            val varObj = element.asJsonObject
                            val variable = LuaPandaVariable(
                                name = varObj.get("name")?.asString ?: "",
                                value = varObj.get("value")?.asString,
                                type = varObj.get("type")?.asString,
                                variablesReference = varObj.get("variablesReference")?.asInt ?: 0
                            )
                            children.add(variable.name, LuaPandaValue(debugProcess, variable, stackId))
                        }
                    }
                    
                    node.addChildren(children, true)
                } catch (e: Exception) {
                    println(" 解析栈帧变量响应失败: ${e.message}")
                    node.addChildren(XValueChildrenList.EMPTY, true)
                }
            })
        }
    }

    override fun customizePresentation(component: ColoredTextContainer) {
        component.append(stack.name ?: "unknown", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        
        // 获取相对路径或文件名
        val displayPath = getDisplayPath(stack.file)
        component.append(" ($displayPath:${stack.line})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
    
    private fun getDisplayPath(filePath: String): String {
        // 尝试获取项目根目录
        val project = debugProcess.session.project
        val projectBasePath = project.basePath
        
        return if (projectBasePath != null && filePath.startsWith(projectBasePath)) {
            // 返回相对于项目根目录的相对路径
            val relativePath = filePath.substring(projectBasePath.length)
            // 移除开头的路径分隔符
            if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
                relativePath.substring(1)
            } else {
                relativePath
            }
        } else {
            // 如果无法获取相对路径，则只显示文件名
            val file = java.io.File(filePath)
            file.name
        }
    }
}

class LuaPandaValue(
    private val debugProcess: LuaPandaDebugProcess,
    private val variable: LuaPandaVariable,
    private val stackId: Int = 0
) : XValue() {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val type = variable.type ?: "unknown"
        val value = variable.value ?: "nil"
        
        val icon = getIcon(type)
        
        node.setPresentation(icon, type, value, variable.variablesReference > 0 || (variable.children != null && variable.children.isNotEmpty()))
    }

    override fun computeChildren(node: XCompositeNode) {
        if (variable.children != null && variable.children.isNotEmpty()) {
            val children = XValueChildrenList()
            variable.children.forEach { child ->
                children.add(child.name, LuaPandaValue(debugProcess, child, stackId))
            }
            node.addChildren(children, true)
        } else if (variable.variablesReference > 0) {
            // 发送getVariable请求获取子变量
            val info = JsonObject().apply {
                addProperty("varRef", variable.variablesReference.toString())
                addProperty("stackId", stackId.toString())
            }
            
            // GET_VARIABLE是响应协议，需要回调
            debugProcess.transporter?.commandToDebugger(LuaPandaCommands.GET_VARIABLE, info, { response ->
                try {
                    val children = XValueChildrenList()
                    
                    // 解析返回的变量信息 - info字段直接是变量数组
                    if (response.info?.isJsonArray == true) {
                        response.info.asJsonArray.forEach { element ->
                            val varObj = element.asJsonObject
                            val childVar = LuaPandaVariable(
                                name = varObj.get("name")?.asString ?: "",
                                value = varObj.get("value")?.asString,
                                type = varObj.get("type")?.asString,
                                variablesReference = varObj.get("variablesReference")?.asInt ?: 0
                            )
                            children.add(childVar.name, LuaPandaValue(debugProcess, childVar, stackId))
                        }
                    }
                    
                    node.addChildren(children, true)
                } catch (e: Exception) {
                    println(" 解析getVariable响应失败: ${e.message}")
                    node.addChildren(XValueChildrenList.EMPTY, true)
                }
            })
        } else {
            node.addChildren(XValueChildrenList.EMPTY, true)
        }
    }
    
    private fun getIcon(type: String?): Icon? {
        return when (type) {
            "string" -> com.intellij.icons.AllIcons.Debugger.Db_primitive
            "number" -> com.intellij.icons.AllIcons.Debugger.Db_primitive
            "boolean" -> com.intellij.icons.AllIcons.Debugger.Db_primitive
            "table" -> com.intellij.icons.AllIcons.Debugger.Db_array
            "function" -> com.intellij.icons.AllIcons.Nodes.Function
            "userdata" -> com.intellij.icons.AllIcons.Debugger.Value
            "thread" -> com.intellij.icons.AllIcons.Debugger.ThreadSuspended
            else -> com.intellij.icons.AllIcons.Debugger.Value
        }
    }
}
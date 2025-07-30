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

import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleTextAttributes
import javax.swing.Icon

class LuaPandaValue(
    private val debugProcess: LuaPandaDebugProcess,
    private val variable: LuaPandaVariable
) : XValue() {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val icon = getIcon(variable.type)
        val value = variable.value ?: "nil"
        val type = variable.type ?: "unknown"
        
        node.setPresentation(icon, type, value, variable.children != null)
    }

    override fun computeChildren(node: XCompositeNode) {
        val children = XValueChildrenList()
        
        variable.children?.forEach { child ->
            children.add(child.name, LuaPandaValue(debugProcess, child))
        }
        
        node.addChildren(children, true)
    }

    private fun getIcon(type: String?): Icon {
        return when (type) {
            "string" -> AllIcons.Debugger.Db_primitive
            "number" -> AllIcons.Debugger.Db_primitive
            "boolean" -> AllIcons.Debugger.Db_primitive
            "table" -> AllIcons.Debugger.Db_array
            "function" -> AllIcons.Nodes.Function
            "userdata" -> AllIcons.Debugger.Value
            "thread" -> AllIcons.Debugger.ThreadSuspended
            else -> AllIcons.Debugger.Value
        }
    }

    override fun canNavigateToSource(): Boolean {
        return false
    }

    override fun canNavigateToTypeSource(): Boolean {
        return false
    }
}
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

package com.tang.intellij.lua.debugger.emmy.value

import com.intellij.icons.AllIcons
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.tang.intellij.lua.debugger.LuaXBoolPresentation
import com.tang.intellij.lua.debugger.LuaXNumberPresentation
import com.tang.intellij.lua.debugger.LuaXStringPresentation
import com.tang.intellij.lua.debugger.emmy.EmmyDebugStackFrame
import com.tang.intellij.lua.debugger.emmy.LuaValueType
import com.tang.intellij.lua.debugger.emmy.VariableValue
import com.tang.intellij.lua.lang.LuaIcons
import java.util.*

abstract class LuaXValue(val value: VariableValue) : XValue() {
    companion object {
        fun create(v: VariableValue, frame: EmmyDebugStackFrame): LuaXValue {
            return when(v.valueTypeValue) {
                LuaValueType.TSTRING -> StringXValue(v)
                LuaValueType.TNUMBER -> NumberXValue(v)
                LuaValueType.TBOOLEAN -> BoolXValue(v)
                LuaValueType.TFUNCTION -> AnyXValue(v)
                LuaValueType.TUSERDATA,
                LuaValueType.TTABLE -> TableXValue(v, frame)
                LuaValueType.GROUP -> GroupXValue(v, frame)
                else -> AnyXValue(v)
            }
        }
    }

    val name: String get() {
        return value.nameValue
    }

    var parent: LuaXValue? = null
}

private object VariableComparator : Comparator<VariableValue> {
    override fun compare(o1: VariableValue, o2: VariableValue): Int {
        val w1 = if (o1.fake) 0 else 1
        val w2 = if (o2.fake) 0 else 1
        if (w1 != w2)
            return w1.compareTo(w2)
        return o1.nameValue.compareTo(o2.nameValue)
    }
}

class StringXValue(v: VariableValue) : LuaXValue(v) {
    override fun computePresentation(xValueNode: XValueNode, place: XValuePlace) {
        xValueNode.setPresentation(AllIcons.Debugger.Db_primitive, LuaXStringPresentation(value.value), false)
    }
}

class NumberXValue(v: VariableValue) : LuaXValue(v) {
    override fun computePresentation(xValueNode: XValueNode, place: XValuePlace) {
        xValueNode.setPresentation(AllIcons.Debugger.Db_primitive, LuaXNumberPresentation(value.value), false)
    }
}

class BoolXValue(val v: VariableValue) : LuaXValue(v) {
    override fun computePresentation(xValueNode: XValueNode, place: XValuePlace) {
        xValueNode.setPresentation(AllIcons.Debugger.Db_primitive, LuaXBoolPresentation(v.value), false)
    }
}

class AnyXValue(val v: VariableValue) : LuaXValue(v) {
    override fun computePresentation(xValueNode: XValueNode, place: XValuePlace) {
        val icon = getIcon(v.valueTypeValue)
        xValueNode.setPresentation(icon, v.valueTypeName, v.value, false)
    }
    
    private fun getIcon(valueType: LuaValueType): javax.swing.Icon? {
        return when (valueType) {
            LuaValueType.TSTRING -> AllIcons.Debugger.Db_primitive
            LuaValueType.TNUMBER -> AllIcons.Debugger.Db_primitive
            LuaValueType.TBOOLEAN -> AllIcons.Debugger.Db_primitive
            LuaValueType.TTABLE -> AllIcons.Debugger.Db_array
            LuaValueType.TFUNCTION -> AllIcons.Nodes.Function
            LuaValueType.TUSERDATA -> AllIcons.Debugger.Value
            LuaValueType.TTHREAD -> AllIcons.Debugger.ThreadSuspended
            LuaValueType.TLIGHTUSERDATA -> AllIcons.Debugger.Value
            LuaValueType.TNIL -> AllIcons.Debugger.Value
            LuaValueType.GROUP -> AllIcons.Nodes.UpLevel
        }
    }
}

class GroupXValue(v: VariableValue, val frame: EmmyDebugStackFrame) : LuaXValue(v) {
    private val children = mutableListOf<LuaXValue>()

    init {
        value.children?.
                sortedWith(VariableComparator)?.
                forEach {
                    children.add(create(it, frame))
                }
    }

    override fun computePresentation(xValueNode: XValueNode, place: XValuePlace) {
        xValueNode.setPresentation(AllIcons.Nodes.UpLevel, value.valueTypeName, value.value, true)
    }

    override fun computeChildren(node: XCompositeNode) {
        val cl = XValueChildrenList()
        children.forEach {
            it.parent = this
            cl.add(it.name, it)
        }
        node.addChildren(cl, true)
    }
}

class TableXValue(v: VariableValue, val frame: EmmyDebugStackFrame) : LuaXValue(v) {

    private val children = mutableListOf<LuaXValue>()

    /**
     * 只读暴露已缓存的 children，供外部（如 inline value painter）**纯本地**查询使用。
     * 调用方只应读取，绝不可触发 eval —— 这里返回的列表可能为空，外部需自行处理。
     */
    val cachedChildren: List<LuaXValue> get() = children

    init {
        value.children?.
                sortedWith(VariableComparator)?.
                forEach {
            children.add(create(it, frame))
        }
    }

    override fun computePresentation(xValueNode: XValueNode, place: XValuePlace) {
        var icon = AllIcons.Debugger.Db_array  // 默认使用table图标
        if (value.valueTypeName == "C#") {
            icon = LuaIcons.CSHARP
        }
        else if (value.valueTypeName == "C++") {
            icon = LuaIcons.CPP
        }
        // 在树视图 / 变量面板里保持原来的显示（`table` / `C++` 等 + 后端给的地址字符串）。
        // 在内联预览（INLINE） / 悬浮气泡（TOOLTIP） 下，用已缓存的 children 拼一个更可读的
        // 摘要字符串，例如 {a=1, b="hi", c={...}}。这完全基于本地数据，不会触发任何
        // 后端 eval，避免再次走到 UnLua 的 __index -> Class_Index 崩溃路径。
        val summary = buildInlineSummary()
        xValueNode.setPresentation(icon, value.valueTypeName, summary, true)
    }

    /**
     * 判断一个子节点是否是后端用于表达 metatable 的伪条目，例如：
     *   - `(metatable)`       —— 元表本身（nil / table）
     *   - `(metatable.__index)` —— 元表的 __index
     * 这些条目的 `name` 是带括号的固定字符串，`fake` 字段并不可靠
     * （valueType 可能是 TTABLE 或 TNIL，都不走 fake 分支），
     * 因此只能按名字判定，统一在摘要里跳过。
     */
    private fun isMetatablePseudoChild(cv: VariableValue): Boolean {
        val n = cv.name
        return n.startsWith("(metatable")
    }

    /**
     * 根据当前已缓存的 children 拼出一个简短摘要字符串：
     * - table：`{k1=v1, k2=v2, ...}`
     * - userdata / C++ / C#：`ClassName { field1=val, field2=val, ... }`
     * - 无 children：回退到后端原始 value（例如 "table: 0x1234..."）
     *
     * 注意：仅使用 valueType 为基础类型的子节点做 value 渲染，避免递归展开 nested table
     * 时需要二次 eval；对 table/userdata 子节点只显示类型名占位（`{...}`）。
     */
    private fun buildInlineSummary(): String {
        if (children.isEmpty()) return value.value

        val maxItems = 5
        val maxLen = 120

        val parts = mutableListOf<String>()
        var shown = 0
        for (child in children) {
            if (child.value.fake) continue // 跳过 GROUP 等伪节点
            if (isMetatablePseudoChild(child.value)) continue // 跳过 metatable 伪条目
            if (shown >= maxItems) {
                parts.add("...")
                break
            }
            val k = child.value.nameValue
            val v = renderChildValue(child.value)
            parts.add("$k=$v")
            shown++
        }

        // 若全被过滤（例如 self 首包只有 metatable 伪条目），退回后端原始 value，
        // 避免展示空 `{}` 或误导性的 `{[(metatable)]=nil}`
        if (parts.isEmpty()) return value.value

        val body = parts.joinToString(", ")
        val prefix = if (value.valueTypeName.isNotEmpty() &&
                         value.valueTypeName != "table" &&
                         value.valueTypeName != "userdata") {
            "${value.valueTypeName} "
        } else ""
        val full = "$prefix{$body}"
        return if (full.length > maxLen) full.substring(0, maxLen - 3) + "..." else full
    }

    private fun renderChildValue(cv: VariableValue): String {
        return when (cv.valueTypeValue) {
            LuaValueType.TSTRING -> "\"${cv.value}\""
            LuaValueType.TNIL -> "nil"
            LuaValueType.TBOOLEAN, LuaValueType.TNUMBER -> cv.value
            LuaValueType.TTABLE, LuaValueType.TUSERDATA -> "{...}"
            LuaValueType.TFUNCTION -> "function"
            else -> cv.value
        }
    }

    override fun computeChildren(node: XCompositeNode) {
        // userdata 类型尤其危险：UnLua 的 __index 元方法在某些上下文下
        // （如调用栈处于 line hook 内、对象的 Container 不可用）会崩溃。
        // 对这类类型一律只使用栈帧 Stack 包里已带回的 children（可能为空或只有 metatable），
        // 绝不再次主动 eval，以避免走到 Class_Index -> Property->Read 的危险路径。
        if (value.valueTypeValue == LuaValueType.TUSERDATA) {
            val cl = XValueChildrenList()
            children.forEach {
                it.parent = this@TableXValue
                cl.add(it.name, it)
            }
            node.addChildren(cl, true)
            return
        }

        // 普通 TTABLE：每次展开都重新 eval 取完整子节点。
        // —— 不能用“children 非空就直接返回缓存”的快速路径，因为后端栈帧首包对
        // 带元表的 OO 风格 table（如 `self`）只会附带 metatable 相关的伪条目
        // （`(metatable)` / `(metatable.__index)`，valueType 仍为 TTABLE，fake=false），
        // 真实字段要等展开时 eval depth=2 才下发。
        // 若误走快速路径就会看到“self 展开只有两个 metatable 条目”的问题。

        val ev = this.frame.evaluator
        if (ev != null) {
            ev.eval(evalExpr, value.cacheId, object : XDebuggerEvaluator.XEvaluationCallback {
                override fun errorOccurred(err: String) {
                    node.setErrorMessage(err)
                }

                override fun evaluated(value: XValue) {
                    if (value is TableXValue) {
                        val cl = XValueChildrenList()
                        children.clear()
                        children.addAll(value.children)
                        children.forEach {
                            it.parent = this@TableXValue
                            cl.add(it.name, it)
                        }
                        node.addChildren(cl, true)
                    }
                    else { // todo: table is nil?
                        node.setErrorMessage("nil")
                    }
                }

            }, 2)
        }
        else super.computeChildren(node)
    }

    private val evalExpr: String
        get() {
            var name = name
            val properties = ArrayList<String>()
            var parent = this.parent
            while (parent != null) {
                if (!parent.value.fake) {
                    properties.add(name)
                    name = parent.name
                }
                parent = parent.parent
            }

            val sb = StringBuilder(name)
            for (i in properties.indices.reversed()) {
                val parentName = properties[i]
                if (parentName.startsWith("["))
                    sb.append(parentName)
                else
                    sb.append(String.format("[\"%s\"]", parentName))
            }
            return sb.toString()
        }
}
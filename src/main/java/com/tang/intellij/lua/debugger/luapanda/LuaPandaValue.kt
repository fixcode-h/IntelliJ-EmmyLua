package com.tang.intellij.lua.debugger.luapanda

import com.intellij.icons.AllIcons
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.serialization.json.*
import javax.swing.Icon

/**
 * LuaPanda变量值
 * 表示调试时的变量值
 */
class LuaPandaValue(
    private val debugProcess: LuaPandaDebugProcess,
    private val valueInfo: JsonObject,
    private val frameIndex: Int
) : XValue() {
    
    private val valueType = valueInfo["type"]?.jsonPrimitive?.content ?: "unknown"
    private val valueString = valueInfo["value"]?.jsonPrimitive?.content ?: "nil"
    private val hasChildren = valueInfo["hasChildren"]?.jsonPrimitive?.content?.toBoolean() ?: false
    
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val presentation = object : XValuePresentation() {
            override fun getType(): String = valueType
            override fun renderValue(renderer: XValueTextRenderer) {
                when (valueType) {
                    "string" -> {
                        renderer.renderStringValue(valueString, "\"", XValueNode.MAX_VALUE_LENGTH)
                    }
                    "number" -> {
                        renderer.renderNumericValue(valueString)
                    }
                    "boolean" -> {
                        renderer.renderKeywordValue(valueString)
                    }
                    "nil" -> {
                        renderer.renderKeywordValue("nil")
                    }
                    "function" -> {
                        renderer.renderValue("function")
                    }
                    "table" -> {
                        renderer.renderValue("table")
                    }
                    else -> {
                        renderer.renderValue(valueString)
                    }
                }
            }
        }
        
        val icon = getIcon()
        node.setPresentation(icon, presentation, hasChildren)
    }
    
    override fun computeChildren(node: XCompositeNode) {
        if (!hasChildren) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }
        
        val variableRef = valueInfo["variableReference"]?.jsonPrimitive?.content
        if (variableRef == null) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }
        
        val command = buildJsonObject {
            put("cmd", "getVariable")
            put("info", buildJsonObject {
                put("variableReference", variableRef)
                put("frameIndex", frameIndex)
            })
        }
        
        val future = debugProcess.sendCommand(command, true)
        future?.thenAccept { response ->
            try {
                val children = XValueChildrenList()
                val variables = response["variables"]?.jsonArray
                
                variables?.forEach { varElement ->
                    val varObj = varElement.jsonObject
                    val name = varObj["name"]?.jsonPrimitive?.content ?: "unknown"
                    val value = LuaPandaValue(debugProcess, varObj, frameIndex)
                    children.add(name, value)
                }
                
                node.addChildren(children, true)
            } catch (e: Exception) {
                node.addChildren(XValueChildrenList.EMPTY, true)
            }
        } ?: run {
            node.addChildren(XValueChildrenList.EMPTY, true)
        }
    }
    
    private fun getIcon(): Icon? {
        return when (valueType) {
            "function" -> AllIcons.Nodes.Function
            "table" -> AllIcons.Nodes.DataTables
            "string" -> AllIcons.Nodes.Property
            "number" -> AllIcons.Nodes.Property
            "boolean" -> AllIcons.Nodes.Property
            else -> AllIcons.Nodes.Variable
        }
    }
}
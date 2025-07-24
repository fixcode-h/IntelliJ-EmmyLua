package com.tang.intellij.lua.debugger.luapanda

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import kotlinx.serialization.json.*

/**
 * LuaPanda栈帧
 * 表示调用栈中的一个帧
 */
class LuaPandaStackFrame(
    private val debugProcess: LuaPandaDebugProcess,
    private val frameInfo: JsonObject,
    private val frameIndex: Int
) : XStackFrame() {
    
    override fun getSourcePosition(): XSourcePosition? {
        try {
            val filePath = frameInfo["file"]?.jsonPrimitive?.content ?: return null
            val line = frameInfo["line"]?.jsonPrimitive?.intOrNull ?: return null
            
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return null
            
            return object : XSourcePosition {
                override fun getFile() = virtualFile
                override fun getLine() = line - 1 // 转换为0基索引
                override fun getOffset() = -1
                override fun createNavigatable(project: com.intellij.openapi.project.Project) = 
                    com.intellij.pom.Navigatable.EMPTY_NAVIGATABLE_ARRAY.first()
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    override fun getEvaluator(): XDebuggerEvaluator? {
        return LuaPandaDebuggerEvaluator(debugProcess, frameIndex)
    }
    
    override fun customizePresentation(component: ColoredTextContainer) {
        val functionName = frameInfo["func"]?.jsonPrimitive?.content ?: "unknown"
        val fileName = frameInfo["file"]?.jsonPrimitive?.content?.let { 
            java.io.File(it).name 
        } ?: "unknown"
        val line = frameInfo["line"]?.jsonPrimitive?.intOrNull ?: 0
        
        component.append("$functionName", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        component.append(" at ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        component.append("$fileName:$line", SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
    
    override fun computeChildren(node: XCompositeNode) {
        // 获取局部变量
        val command = buildJsonObject {
            put("cmd", "getVariable")
            putJsonObject("info") {
                put("frameIndex", frameIndex)
                put("varType", "local")
            }
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
}
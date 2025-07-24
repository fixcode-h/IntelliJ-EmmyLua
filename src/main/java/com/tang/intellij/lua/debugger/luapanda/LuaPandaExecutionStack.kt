package com.tang.intellij.lua.debugger.luapanda

import com.intellij.icons.AllIcons
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.serialization.json.*

/**
 * LuaPanda执行栈
 * 表示调试时的调用栈
 */
class LuaPandaExecutionStack(
    private val debugProcess: LuaPandaDebugProcess,
    private val stopInfo: JsonObject
) : XExecutionStack("Main Thread", AllIcons.Debugger.ThreadSuspended) {
    
    override fun getTopFrame(): XStackFrame? {
        return LuaPandaStackFrame(debugProcess, stopInfo, 0)
    }
    
    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer?) {
        // 获取完整的调用栈
        val command = buildJsonObject {
            put("cmd", "getStack")
        }
        
        val future = debugProcess.sendCommand(command, true)
        future?.thenAccept { response ->
            try {
                val frames = mutableListOf<XStackFrame>()
                val stackArray = response["stack"]?.jsonArray
                
                stackArray?.forEachIndexed { index, frameElement ->
                    if (index >= firstFrameIndex) {
                        val frameObj = frameElement.jsonObject
                        frames.add(LuaPandaStackFrame(debugProcess, frameObj, index))
                    }
                }
                
                container?.addStackFrames(frames, true)
            } catch (e: Exception) {
                container?.addStackFrames(emptyList(), true)
            }
        } ?: run {
            // 如果无法获取栈信息，至少返回顶层帧
            val topFrame = getTopFrame()
            if (topFrame != null) {
                container?.addStackFrames(listOf(topFrame), true)
            } else {
                container?.addStackFrames(emptyList(), true)
            }
        }
    }
}
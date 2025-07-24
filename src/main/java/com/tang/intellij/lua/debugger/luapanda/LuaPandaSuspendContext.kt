package com.tang.intellij.lua.debugger.luapanda

import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XSuspendContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LuaPanda挂起上下文
 * 表示调试器挂起时的状态
 */
class LuaPandaSuspendContext(
    private val debugProcess: LuaPandaDebugProcess,
    private val stopInfo: JsonObject
) : XSuspendContext() {
    
    private val executionStack = LuaPandaExecutionStack(debugProcess, stopInfo)
    
    override fun getActiveExecutionStack(): XExecutionStack {
        return executionStack
    }
    
    override fun getExecutionStacks(): Array<XExecutionStack> {
        return arrayOf(executionStack)
    }
}
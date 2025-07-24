package com.tang.intellij.lua.debugger.luapanda

import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture

/**
 * LuaPanda调试器表达式求值器
 */
class LuaPandaDebuggerEvaluator(
    private val debugProcess: LuaPandaDebugProcess,
    private val frameIndex: Int
) : XDebuggerEvaluator() {
    
    override fun evaluate(
        expression: String,
        callback: XEvaluationCallback,
        expressionPosition: com.intellij.xdebugger.XSourcePosition?
    ) {
        val command = buildJsonObject {
            put("cmd", "eval")
            putJsonObject("info") {
                put("expr", expression)
                put("frameIndex", frameIndex)
            }
        }
        
        val future = debugProcess.sendCommand(command, true)
        future?.thenAccept { response ->
            try {
                val success = response["success"]?.jsonPrimitive?.content?.toBoolean() ?: false
                if (success) {
                    val result = response["result"]
                    if (result != null) {
                        val value = LuaPandaValue(debugProcess, result.jsonObject, frameIndex)
                        callback.evaluated(value)
                    } else {
                        callback.errorOccurred("No result returned")
                    }
                } else {
                    val error = response["error"]?.jsonPrimitive?.content ?: "Evaluation failed"
                    callback.errorOccurred(error)
                }
            } catch (e: Exception) {
                callback.errorOccurred("Failed to evaluate expression: ${e.message}")
            }
        } ?: run {
            callback.errorOccurred("Failed to send evaluation command")
        }
    }
}
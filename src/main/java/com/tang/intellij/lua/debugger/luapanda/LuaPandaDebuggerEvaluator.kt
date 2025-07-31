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

import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.tang.intellij.lua.debugger.LuaDebuggerEvaluator
import com.google.gson.JsonObject

/**
 * LuaPanda调试器的表达式求值器
 */
class LuaPandaDebuggerEvaluator(
    private val debugProcess: LuaPandaDebugProcess,
    private val stackFrame: LuaPandaStackFrame
) : LuaDebuggerEvaluator() {

    override fun eval(express: String, xEvaluationCallback: XDebuggerEvaluator.XEvaluationCallback, xSourcePosition: XSourcePosition?) {
        val info = JsonObject().apply {
            addProperty("varName", express)
            addProperty("stackId", stackFrame.stackId)
        }
        
        debugProcess.transporter?.commandToDebugger(LuaPandaCommands.GET_WATCHED_VARIABLE, info, { response ->
            try {
                val infoArray = response.info?.asJsonArray
                if (infoArray != null && infoArray.size() > 0) {
                    // 解析getWatchedVariable的返回结果 - info是数组格式
                    val varInfo = infoArray.get(0).asJsonObject
                    val variable = LuaPandaVariable(
                        name = express,
                        value = varInfo.get("value")?.asString,
                        type = varInfo.get("type")?.asString,
                        variablesReference = varInfo.get("variablesReference")?.asInt ?: 0
                    )
                    val luaValue = LuaPandaValue(debugProcess, variable, stackFrame.stackId)
                    xEvaluationCallback.evaluated(luaValue)
                } else {
                    xEvaluationCallback.errorOccurred("求值失败：无法解析响应")
                }
            } catch (e: Exception) {
                xEvaluationCallback.errorOccurred("求值失败：${e.message}")
            }
        })
    }
}
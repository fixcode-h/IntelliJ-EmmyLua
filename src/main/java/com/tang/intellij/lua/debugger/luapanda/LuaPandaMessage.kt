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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tang.intellij.lua.LuaBundle

data class LuaPandaMessage(
    val cmd: String,
    val info: JsonElement?,
    val callbackId: String,
    val stack: List<LuaPandaStack>? = null
) {
    // 便利方法来获取info作为JsonObject
    fun getInfoAsObject(): JsonObject? {
        return if (info?.isJsonObject == true) info.asJsonObject else null
    }
}

data class LuaPandaStack(
    val file: String,
    val line: String,  // Lua端发送的是字符串格式的行号
    val name: String,  // 对应Lua端的name字段
    val index: String, // 对应Lua端的index字段
    val oPath: String? = null, // 对应Lua端的oPath字段
    val locals: List<LuaPandaVariable>? = null,
    val upvalues: List<LuaPandaVariable>? = null
) {
    // 提供一个便利方法来获取整数行号
    fun getLineNumber(): Int {
        return line.toIntOrNull() ?: 0
    }
    
    // 提供一个便利方法来获取索引
    fun getIndex(): Int {
        return index.toIntOrNull() ?: 0
    }
}

data class LuaPandaVariable(
    val name: String,
    val value: String?,
    val type: String?,
    val variablesReference: Int = 0,
    val children: List<LuaPandaVariable>? = null
)

data class LuaPandaBreakpoint(
    val path: String,
    val bks: List<BreakpointInfo>
)

data class BreakpointInfo(
    val verified: Boolean = true,
    val type: Int = 2,
    val line: Int
)

data class LuaPandaInitInfo(
    val stopOnEntry: String,
    val useCHook: String,
    val logLevel: String,
    val luaFileExtension: String = "lua",
    val cwd: String,
    val isNeedB64EncodeStr: String = "false",
    val TempFilePath: String,
    val pathCaseSensitivity: String = "true",
    val osType: String,
    val clibPath: String = "",
    val adapterVersion: String = "1.0.0",
    val autoPathMode: String = "false",
    val distinguishSameNameFile: String = "false",
    val truncatedOPath: String = "",
    val developmentMode: String = "false"
)

object LuaPandaCommands {
    const val INIT_SUCCESS = "initSuccess"
    const val SET_BREAKPOINT = "setBreakPoint"  // 注意大小写
    const val STOP_ON_BREAKPOINT = "stopOnBreakpoint"
    const val STOP_ON_ENTRY = "stopOnEntry"
    const val CONTINUE = "continue"
    const val STEP_OVER = "stopOnStep"  // Lua中使用的是stopOnStep
    const val STEP_IN = "stopOnStepIn"  // Lua中使用的是stopOnStepIn
    const val STEP_OUT = "stopOnStepOut"  // Lua中使用的是stopOnStepOut
    const val STOP_RUN = "stopRun"
    const val OUTPUT = "output"
    const val GET_VARIABLE = "getVariable"
    const val SET_VARIABLE = "setVariable"
    const val GET_WATCHED_VARIABLE = "getWatchedVariable"
}

enum class LuaPandaTransportType(val desc: String) {
    TCP_CLIENT(LuaBundle.message("debugger.transport.tcp_client")),
    TCP_SERVER(LuaBundle.message("debugger.transport.tcp_server"));

    override fun toString(): String {
        return desc
    }
}
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

import com.google.gson.JsonObject

data class LuaPandaMessage(
    val cmd: String,
    val info: JsonObject?,
    val callbackId: String
)

data class LuaPandaStack(
    val file: String,
    val line: Int,
    val functionName: String?,
    val locals: List<LuaPandaVariable>? = null,
    val upvalues: List<LuaPandaVariable>? = null
)

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
    val line: Int,
    val condition: String? = null,
    val logMessage: String? = null
)

data class LuaPandaInitInfo(
    val stopOnEntry: String,
    val useCHook: String,
    val logLevel: String,
    val luaFileExtension: String = "lua",
    val cwd: String,
    val isNeedB64EncodeStr: String = "false",
    val tempFilePath: String,
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

enum class LuaPandaTransportType {
    TCP_CLIENT,
    TCP_SERVER
}
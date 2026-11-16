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

package com.tang.intellij.lua.debug.ui.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 调试的全局设置（Settings > Tools > EmmyLua 调试）。
 *
 * MCP、设备、引擎实现都直接读这里，不各自存一份：端口只有一个真相，
 * 设置页改了以后调 LuaMcpService.applySettings() 让服务跟上。
 */
@Service(Service.Level.APP)
@State(name = "EmmyLuaDebugSettings", storages = [Storage("emmylua-debug.xml")])
class LuaDebugSettings : SimplePersistentStateComponent<LuaDebugSettings.State>(State()) {

    class State : BaseState() {
        var mcpEnabled by property(true)
        var mcpPort by property(DEFAULT_MCP_PORT)
        /** 允许 Agent 用 mode=lua 执行任意表达式；默认只允许读变量路径（设计文档 6.2）。 */
        var allowAgentLuaEval by property(false)
        /** 空 = 自动找（ANDROID_HOME / PATH）。 */
        var adbPath by string("")
        /** 未裁剪 so 的搜索目录，分号分隔，支持 $PROJECT_DIR$。 */
        var symbolSearchDirs by string(DEFAULT_SYMBOL_DIRS)
        var logLevel by enum(LuaDebugLogLevel.RUNTIME)
    }

    var mcpEnabled: Boolean
        get() = state.mcpEnabled
        set(value) { state.mcpEnabled = value }

    var mcpPort: Int
        get() = state.mcpPort
        set(value) { state.mcpPort = value }

    var allowAgentLuaEval: Boolean
        get() = state.allowAgentLuaEval
        set(value) { state.allowAgentLuaEval = value }

    var adbPath: String
        get() = state.adbPath.orEmpty()
        set(value) { state.adbPath = value.trim() }

    var symbolSearchDirs: List<String>
        get() = state.symbolSearchDirs.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) { state.symbolSearchDirs = value.joinToString(";") }

    var logLevel: LuaDebugLogLevel
        get() = state.logLevel
        set(value) { state.logLevel = value }

    /** 展开 $PROJECT_DIR$ 后的搜索目录。 */
    fun expandedSymbolSearchDirs(project: Project?): List<String> {
        val base = project?.basePath
        return symbolSearchDirs.map { dir ->
            if (base != null) dir.replace("\$PROJECT_DIR\$", base) else dir
        }
    }

    companion object {
        const val DEFAULT_MCP_PORT = 9330
        const val DEFAULT_SYMBOL_DIRS = "\$PROJECT_DIR\$/Binaries/Android;\$PROJECT_DIR\$/Intermediate/Android"

        fun getInstance(): LuaDebugSettings = service()
    }
}

enum class LuaDebugLogLevel {
    /** 只显示 Lua 输出和错误。 */
    RUNTIME,
    /** 加上连接、握手、断点下发。 */
    VERBOSE,
    /** 打印每一帧协议，排查问题用。 */
    PROTOCOL,
}

/** 按项目记住的东西：未安装提示是否不再弹、最近附加过的进程。 */
@Service(Service.Level.PROJECT)
@State(name = "EmmyLuaDebugProjectState", storages = [Storage("emmylua-debug.xml")])
class LuaDebugProjectState : SimplePersistentStateComponent<LuaDebugProjectState.State>(State()) {

    class State : BaseState() {
        var mcpPromptDismissed by property(false)
        /** 最近附加过的可执行文件路径（或进程名），新的在前。 */
        var recentAttachTargets by list<String>()
    }

    var mcpPromptDismissed: Boolean
        get() = state.mcpPromptDismissed
        set(value) { state.mcpPromptDismissed = value }

    val recentAttachTargets: List<String> get() = state.recentAttachTargets.toList()

    fun rememberAttach(key: String) {
        val list = state.recentAttachTargets
        list.remove(key)
        list.add(0, key)
        while (list.size > MAX_RECENT) list.removeAt(list.size - 1)
        state.intIncrementModificationCount()
    }

    companion object {
        private const val MAX_RECENT = 5

        fun getInstance(project: Project): LuaDebugProjectState = project.service()
    }
}

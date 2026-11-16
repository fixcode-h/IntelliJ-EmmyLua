package com.tang.intellij.lua.debug.xdebugger

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.tang.intellij.lua.debug.engine.DebugSession
import com.tang.intellij.lua.debug.engine.SessionConfig
import com.tang.intellij.lua.debug.protocol.SetOptionsArgs
import com.tang.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.tang.intellij.lua.debug.ui.settings.VariableViewSettings
import java.io.File

/**
 * 变量视图设置（设置页「变量视图」）→ 引擎：握手时随 SessionConfig 下发，设置变化时推给所有会话
 * （variable-view.md 13.4、13.5、15.3）。
 */
object LuaVariableViewSync {
    private val LOG = logger<LuaVariableViewSync>()

    fun settings(): VariableViewSettings = LuaDebugSettings.getInstance().variableView

    /** 新会话的初始配置。 */
    fun configure(project: Project, config: SessionConfig): SessionConfig {
        val view = settings()
        return config.copy(
            options = config.options.copy(variableView = protocolOptions(view)),
            viewRules = loadRules(project, view),
            maxValueLength = view.maxValueLength
        )
    }

    /** 设置页点「应用」后调用：已在调试的会话立即生效（下次取变量时）。 */
    fun applyToAllSessions() {
        val view = settings()
        val options = SetOptionsArgs(variableView = protocolOptions(view))
        ApplicationManager.getApplication().executeOnPooledThread {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                val sessions = LuaDebugSessionManager.getInstance(project).sessions()
                if (sessions.isEmpty()) continue
                val rules = loadRules(project, view)
                sessions.forEach { apply(it.engine, options, rules, view) }
            }
        }
    }

    private fun apply(engine: DebugSession, options: SetOptionsArgs, rules: JsonObject?, view: VariableViewSettings) {
        engine.maxValueLength = view.maxValueLength
        engine.updateOptions(options)
        engine.setViewRules(rules)
    }

    private fun protocolOptions(view: VariableViewSettings): JsonObject =
        Gson().toJsonTree(view.toProtocolOptions()).asJsonObject

    /** 规则文件相对项目根；不存在时返回 null（不发），格式不对时记日志并忽略。 */
    private fun loadRules(project: Project, view: VariableViewSettings): JsonObject? {
        val path = view.rulesFile.trim().takeIf { it.isNotEmpty() } ?: return null
        val file = File(path).takeIf { it.isAbsolute } ?: File(project.basePath ?: return null, path)
        if (!file.isFile) return null
        return try {
            JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            LOG.warn("显示规则文件 ${file.path} 不是合法的 JSON 对象，已忽略：${e.message}")
            null
        }
    }
}

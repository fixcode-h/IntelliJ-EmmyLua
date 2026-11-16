package com.tang.intellij.lua.debug.xdebugger

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
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
        watchRulesFiles()
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

    /**
     * 只推显示规则（右键固定 / 隐藏、规则文件被编辑后）。[rebuild] 时等 runtime 收下再刷新变量树：
     * runtime 对新取的引用按新规则排键，刷新会重新取局部变量，所以当前这次暂停里就能看到效果。
     */
    fun pushRules(project: Project, rules: JsonObject?, rebuild: Boolean) {
        val sessions = LuaDebugSessionManager.getInstance(project).sessions()
        sessions.forEach { entry ->
            entry.engine.setViewRules(rules).whenComplete { _, _ ->
                if (rebuild) ApplicationManager.getApplication().invokeLater({ entry.xSession.rebuildViews() }, project.disposed)
            }
        }
    }

    @Volatile
    private var watching = false

    /** 规则文件在编辑器里或外部被改了：重新读、推给这个项目的会话。只在第一次起会话时订阅一次。 */
    private fun watchRulesFiles() {
        if (watching) return
        synchronized(this) {
            if (watching) return
            watching = true
        }
        ApplicationManager.getApplication().messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val paths = events.mapNotNull { it.path.takeIf { p -> p.endsWith(".json") } }
                if (paths.isEmpty()) return
                for (project in ProjectManager.getInstance().openProjects) {
                    if (project.isDisposed) continue
                    val file = LuaViewRulesFile.file(project) ?: continue
                    val target = FileUtil.toSystemIndependentName(file.path)
                    if (paths.none { FileUtil.pathsEqual(FileUtil.toSystemIndependentName(it), target) }) continue
                    if (LuaDebugSessionManager.getInstance(project).sessions().isEmpty()) continue
                    ApplicationManager.getApplication().executeOnPooledThread {
                        pushRules(project, loadRules(project, settings()), rebuild = true)
                    }
                }
            }
        })
    }

    private fun protocolOptions(view: VariableViewSettings): JsonObject =
        Gson().toJsonTree(view.toProtocolOptions()).asJsonObject

    /** 规则文件相对项目根；不存在时返回 null（不发），格式不对时记日志并忽略。 */
    internal fun loadRules(project: Project, view: VariableViewSettings): JsonObject? {
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

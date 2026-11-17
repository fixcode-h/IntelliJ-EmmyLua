package com.fixcode.intellij.lua.project

import com.fixcode.intellij.lua.lsp.EmmyLuaServer
import com.fixcode.intellij.lua.lsp.EmmyLuaWorkspaceConfig
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * UE 声明目录出现或换了（第一次打开 UELink 后台生成、删掉生成目录退回 UnLua 的）时重启 emmylua_ls，让它换库目录。
 * 目录里的文件增删改不用管：emmylua_ls 自己监听库目录，增量更新索引。只看几个路径存不存在，很便宜。
 */
@Service(Service.Level.PROJECT)
class UnrealDeclWatcher(private val project: Project) : Disposable {
    @Volatile private var last: List<Path>? = null
    private var future: ScheduledFuture<*>? = null

    fun start() {
        if (future != null) return
        last = current()
        future = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ check() }, 15, 15, TimeUnit.SECONDS)
    }

    /**
     * 点了按钮、生成刚跑完时马上看，不等下一轮。UELink 空闲 5 秒后才写清单（之前不算生成过），
     * 所以 3 秒看一次不够：再在 8、15 秒各看一次，清单一出现就换库目录。
     */
    fun checkSoon() {
        val executor = AppExecutorUtil.getAppScheduledExecutorService()
        for (delay in longArrayOf(3, 8, 15)) executor.schedule({ check() }, delay, TimeUnit.SECONDS)
    }

    private fun current(): List<Path> =
        if (LuaProjectSettings.getInstance(project).detectUnrealProject) EmmyLuaWorkspaceConfig.unrealLibraries(project) else emptyList()

    /** 上次确认过「已开」的 UE 编辑器进程：编辑器重开（换了 PID）再确认一次，不每轮都发请求。 */
    @Volatile private var syncedPid: Long = -1

    /** 设置里关掉时清掉，下次勾上重新确认 */
    fun resetSync() {
        syncedPid = -1
    }

    @Synchronized
    private fun check() {
        if (project.isDisposed) return
        syncEditor()
        val now = runCatching { current() }.getOrNull() ?: return
        if (now == last) return
        last = now
        EmmyLuaServer.restart(project)
    }

    /** IDE 里勾了开关、UE 编辑器后开（或重开）的，补一次 luadecl.enable；没勾时不动 UE 那边（可能是在控制台里开的） */
    private fun syncEditor() {
        if (!LuaProjectSettings.getInstance(project).unrealLuaDecl) return
        val ue = UnrealDecl.ueRoot(project) ?: return
        val editor = UELinkClient.findEditor(ue) ?: return
        if (editor.pid == syncedPid) return
        runCatching {
            val status = UELinkClient.call(editor, "luadecl.status")
            if (status.get("active")?.asBoolean != true) {
                UELinkClient.call(editor, "luadecl.enable", com.google.gson.JsonObject().apply { addProperty("enabled", true) })
            }
            syncedPid = editor.pid
        }
    }

    override fun dispose() {
        future?.cancel(false)
        future = null
    }

    companion object {
        fun getInstance(project: Project): UnrealDeclWatcher = project.getService(UnrealDeclWatcher::class.java)
    }
}

class UnrealDeclWatcherActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        UnrealDeclWatcher.getInstance(project).start()
        LuaLibraryRoots.getInstance(project).refresh()
    }
}

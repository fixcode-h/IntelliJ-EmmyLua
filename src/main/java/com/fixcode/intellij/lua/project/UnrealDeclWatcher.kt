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

    /** 设置页点了按钮后马上看一次，不等下一轮。 */
    fun checkSoon() {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ check() }, 3, TimeUnit.SECONDS)
    }

    private fun current(): List<Path> =
        if (LuaProjectSettings.getInstance(project).detectUnrealProject) EmmyLuaWorkspaceConfig.unrealLibraries(project) else emptyList()

    @Synchronized
    private fun check() {
        if (project.isDisposed) return
        val now = runCatching { current() }.getOrNull() ?: return
        if (now == last) return
        last = now
        EmmyLuaServer.restart(project)
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
    }
}

package com.fixcode.intellij.lua.project

import com.fixcode.intellij.lua.lsp.EmmyLuaWorkspaceConfig
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Path

/**
 * IDE 这边控制 UE 编辑器里的「后台生成 UE 反射的 Lua 声明」（UELink 2.5+ 的 luadecl.*）：开关、主动刷新。
 * 开关记在 IDE 的项目设置里（[LuaProjectSettings.unrealLuaDecl]，蓝图另有 [LuaProjectSettings.unrealLuaDeclBlueprints]，都默认关）；
 * UE 编辑器后开的，[UnrealDeclWatcher] 发现后自动补开。蓝图开关经 `luadecl.enable` 的 `blueprints` 下发（UELink 2.7+，更老的版本忽略它、照旧生成蓝图）。
 * 都是阻塞调用（本机 HTTP），在后台线程里用；失败抛 IOException，消息是给人看的中文。
 */
object UnrealDecl {
    const val NOTIFICATION_GROUP = "EmmyLua UE Declarations"
    const val BLUEPRINTS_LABEL = "蓝图也生成声明"

    enum class Refresh(val label: String) {
        /** 再比对一遍，只写变了的 */
        CHANGED("刷新"),
        /** 顺带在后台逐个加载没打开过的蓝图，补全成员 */
        LOAD_BLUEPRINTS("补全全部蓝图"),
        /** 不看清单，全部重写 */
        FORCE("全部重写"),
    }

    fun ueRoot(project: Project): Path? =
        EmmyLuaWorkspaceConfig.projectRoot(project)?.let { UnrealIntelliSenseRoots.findUnrealProjectRoot(it) }

    private fun editor(project: Project): UELinkClient.Instance {
        val ue = ueRoot(project) ?: throw IOException("没找到 .uproject：项目目录不在 UE 项目里")
        return UELinkClient.findEditor(ue)
            ?: throw IOException("没找到开着的 UE 编辑器（或项目里没装 UELink 2.5+）：先打开 UE 编辑器")
    }

    fun enableArgs(enabled: Boolean, blueprints: Boolean) = JsonObject().apply {
        addProperty("enabled", enabled)
        addProperty("blueprints", blueprints)
    }

    /** UE 那边的蓝图开关和 IDE 的不一致（老版 UELink 不报这个字段时不算不一致） */
    fun blueprintsDiffer(status: JsonObject, blueprints: Boolean): Boolean =
        status.get("blueprints")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let { it != blueprints } ?: false

    fun setEnabled(project: Project, enabled: Boolean, blueprints: Boolean = LuaProjectSettings.getInstance(project).unrealLuaDeclBlueprints): String {
        val result = UELinkClient.call(editor(project), "luadecl.enable", enableArgs(enabled, blueprints))
        return if (enabled) {
            UnrealDeclWatcher.getInstance(project).checkSoon()
            val what = if (blueprints) "C++ 类和蓝图" else "C++ 反射类型，不含蓝图"
            "UE 编辑器已开始后台生成 Lua 声明（$what，${result.get("dir")?.asString ?: ""}），第一次要几十秒，生成完 IDE 自动换用"
        } else {
            "UE 编辑器已停止生成；已生成的声明留着照样能用"
        }
    }

    /**
     * IDE 里打开 / 关掉后台生成：记进项目设置，再通知 UE 编辑器。设置页「应用」和上手检查共用。
     * UE 编辑器没开时只记下开关，[UnrealDeclWatcher] 发现编辑器打开后自动补开。
     */
    fun setEnabledFromIde(
        project: Project,
        enabled: Boolean,
        blueprints: Boolean = LuaProjectSettings.getInstance(project).unrealLuaDeclBlueprints,
    ) {
        val settings = LuaProjectSettings.getInstance(project)
        settings.unrealLuaDecl = enabled
        settings.unrealLuaDeclBlueprints = blueprints
        UnrealDeclWatcher.getInstance(project).resetSync()
        runInBackground(project) {
            try {
                setEnabled(project, enabled, blueprints)
            } catch (e: IOException) {
                if (enabled) "已记下，UE 编辑器打开后自动开始生成（${e.message}）" else throw e
            }
        }
    }

    /**
     * UE 那边还没开时：IDE 里勾了开关就先打开再刷新，没勾就报错让先勾上。
     * 设置页里勾了还没点「应用」时按界面上的勾选算（[enabledInUi]、[blueprintsInUi]）。
     * UE 那边的蓝图开关和 IDE 不一致时先按 IDE 的改过来。
     */
    fun refresh(project: Project, mode: Refresh, enabledInUi: Boolean? = null, blueprintsInUi: Boolean? = null): String {
        val settings = LuaProjectSettings.getInstance(project)
        val blueprints = blueprintsInUi ?: settings.unrealLuaDeclBlueprints
        if (mode == Refresh.LOAD_BLUEPRINTS && !blueprints) {
            throw IOException("没打开蓝图声明：先在 设置 → EmmyLua → 库目录 勾选「$BLUEPRINTS_LABEL」")
        }
        val editor = editor(project)
        val status = UELinkClient.call(editor, "luadecl.status")
        if (status.get("active")?.asBoolean != true) {
            if (!(enabledInUi ?: settings.unrealLuaDecl)) {
                throw IOException("还没打开后台生成：先在 设置 → EmmyLua → 库目录 勾选「让 UE 编辑器后台生成反射声明」")
            }
            UELinkClient.call(editor, "luadecl.enable", enableArgs(true, blueprints))
        } else if (blueprintsDiffer(status, blueprints)) {
            UELinkClient.call(editor, "luadecl.enable", enableArgs(true, blueprints))
        }
        val args = JsonObject().apply {
            if (mode == Refresh.FORCE) addProperty("force", true)
            if (mode == Refresh.LOAD_BLUEPRINTS) addProperty("loadBlueprints", true)
        }
        val result = UELinkClient.call(editor, "luadecl.run", args)
        UnrealDeclWatcher.getInstance(project).checkSoon()
        return when (mode) {
            Refresh.LOAD_BLUEPRINTS -> "UE 编辑器开始补全蓝图：要加载 ${result.get("toLoad")?.asInt ?: 0} 个资源，在后台每帧加载一个"
            Refresh.FORCE -> "UE 编辑器开始全部重写 Lua 声明"
            Refresh.CHANGED -> "UE 编辑器开始刷新 Lua 声明，只写变了的文件"
        }
    }

    /**
     * 后台任务跑 [task]（连 UE 编辑器、发命令），然后像 IDEA 的索引那样在底部状态栏跟着 UE 那边的生成进度走，
     * 完成后弹通知。点进度条上的取消只是不再跟进度，UE 编辑器里照样生成完。
     */
    fun runInBackground(project: Project, task: () -> String) {
        object : Task.Backgroundable(project, "UE 反射声明", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "正在连接 UE 编辑器…"
                val (text, type) = try {
                    track(project, indicator, task()) to NotificationType.INFORMATION
                } catch (e: IOException) {
                    (e.message ?: "失败") to NotificationType.WARNING
                } catch (e: ProcessCanceledException) {
                    "不再跟进度；UE 编辑器里照样会生成完" to NotificationType.INFORMATION
                }
                notify(project, text, type)
            }
        }.queue()
    }

    /** 设置页的刷新按钮已经发过命令了（[started] 是它的结果文本），只在状态栏跟进度。 */
    fun watchInBackground(project: Project, started: String) = runInBackground(project) { started }

    /**
     * 每隔 [POLL_MS] 查一次 luadecl.status 更新进度条，空闲了返回完成摘要。
     * 刚发完命令 UE 可能还没开始（或者没什么要做的）：一直空闲超过 [IDLE_GRACE_MS] 就按 [started] 收尾。
     */
    private fun track(project: Project, indicator: ProgressIndicator, started: String): String {
        val editor = try { editor(project) } catch (e: IOException) { return started }
        val begin = System.currentTimeMillis()
        var busy = false
        while (true) {
            indicator.checkCanceled()
            val status = UELinkClient.call(editor, "luadecl.status")
            if (status.get("active")?.asBoolean != true) return started
            val progress = UnrealDeclProgress.of(status)
            if (progress == null) {
                if (busy) {
                    UnrealDeclWatcher.getInstance(project).checkSoon()
                    return UnrealDeclProgress.summary(status)
                }
                if (System.currentTimeMillis() - begin > IDLE_GRACE_MS) return started
            } else {
                busy = true
                indicator.text = "UE 反射声明：${progress.text}"
                indicator.isIndeterminate = progress.fraction == null
                progress.fraction?.let { indicator.fraction = it }
            }
            Thread.sleep(POLL_MS)
        }
    }

    private const val POLL_MS = 500L
    private const val IDLE_GRACE_MS = 3000L

    private fun notify(project: Project, text: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("UE 反射声明", text, type)
            .notify(project)
    }
}

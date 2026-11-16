package com.fixcode.intellij.lua.project

import com.fixcode.intellij.lua.lsp.EmmyLuaWorkspaceConfig
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Path

/**
 * IDE 这边控制 UE 编辑器里的「后台生成 UE 反射的 Lua 声明」（UELink 2.5+ 的 luadecl.*）：开关、主动刷新。
 * 开关记在 IDE 的项目设置里（[LuaProjectSettings.unrealLuaDecl]）；UE 编辑器后开的，[UnrealDeclWatcher] 发现后自动补开。
 * 都是阻塞调用（本机 HTTP），在后台线程里用；失败抛 IOException，消息是给人看的中文。
 */
object UnrealDecl {
    const val NOTIFICATION_GROUP = "EmmyLua UE Declarations"

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

    fun setEnabled(project: Project, enabled: Boolean): String {
        val result = UELinkClient.call(editor(project), "luadecl.enable", JsonObject().apply { addProperty("enabled", enabled) })
        return if (enabled) {
            UnrealDeclWatcher.getInstance(project).checkSoon()
            "UE 编辑器已开始后台生成 Lua 声明（${result.get("dir")?.asString ?: ""}），第一次要几十秒，生成完 IDE 自动换用"
        } else {
            "UE 编辑器已停止生成；已生成的声明留着照样能用"
        }
    }

    /**
     * UE 那边还没开时：IDE 里勾了开关就先打开再刷新，没勾就报错让先勾上。
     * 设置页里勾了还没点「应用」时按界面上的勾选算（[enabledInUi]）。
     */
    fun refresh(project: Project, mode: Refresh, enabledInUi: Boolean? = null): String {
        val editor = editor(project)
        val status = UELinkClient.call(editor, "luadecl.status")
        if (status.get("active")?.asBoolean != true) {
            if (!(enabledInUi ?: LuaProjectSettings.getInstance(project).unrealLuaDecl)) {
                throw IOException("还没打开后台生成：先在 设置 → EmmyLua → 库目录 勾选「让 UE 编辑器后台生成反射声明」")
            }
            UELinkClient.call(editor, "luadecl.enable", JsonObject().apply { addProperty("enabled", true) })
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

    /** 后台跑，结果弹通知 */
    fun runInBackground(project: Project, task: () -> String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val (text, type) = try {
                task() to NotificationType.INFORMATION
            } catch (e: IOException) {
                (e.message ?: "失败") to NotificationType.WARNING
            }
            if (!project.isDisposed) {
                NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification("UE 反射声明", text, type)
                    .notify(project)
            }
        }
    }
}

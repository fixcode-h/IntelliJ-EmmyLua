package com.fixcode.intellij.lua.actions

import com.fixcode.intellij.lua.project.LuaProjectSettings
import com.fixcode.intellij.lua.project.UnrealDecl
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/** 工具菜单：让 UE 编辑器马上刷新 UE 反射的 Lua 声明（UELink 2.5+） */
abstract class UnrealDeclActionBase(private val mode: UnrealDecl.Refresh) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        UnrealDecl.runInBackground(project) { UnrealDecl.refresh(project, mode) }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        // 文件系统查找很便宜（往上找 .uproject，最多 8 层）
        e.presentation.isEnabledAndVisible = project != null && UnrealDecl.ueRoot(project) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class RefreshUnrealDeclAction : UnrealDeclActionBase(UnrealDecl.Refresh.CHANGED)
class LoadBlueprintsUnrealDeclAction : UnrealDeclActionBase(UnrealDecl.Refresh.LOAD_BLUEPRINTS)
class RebuildUnrealDeclAction : UnrealDeclActionBase(UnrealDecl.Refresh.FORCE)

/**
 * 主工具栏按钮：UE 编辑器开着时点一下，经 UELink 让它马上生成 / 刷新 Lua 声明。
 * 点按钮就是明确要生成：设置里的开关没勾也替用户勾上（之后 UE 编辑器再启动时 IDE 自动让它接着生成），
 * 不再像菜单里的刷新那样报「先去设置里勾选」。编辑器没开、没装 UELink 时由 [UnrealDecl] 报出原因。
 */
class GenerateUnrealDeclAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LuaProjectSettings.getInstance(project).unrealLuaDecl = true
        UnrealDecl.runInBackground(project) { UnrealDecl.refresh(project, UnrealDecl.Refresh.CHANGED, enabledInUi = true) }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && UnrealDecl.ueRoot(project) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

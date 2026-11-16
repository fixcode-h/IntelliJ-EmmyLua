package com.fixcode.intellij.lua.actions

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

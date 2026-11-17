package com.fixcode.intellij.lua.debug.xdebugger

import com.fixcode.intellij.lua.debug.ue.UnrealObjectReveal
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase

/**
 * 变量树右键「在 UE 中选中」「在 UE 中打开资产」：只对 UELink 显示的 UObject 出现（行尾灰字是对象路径的那些）。
 * UE 编辑器没开、UELink 不到 2.10.0 时置灰，说明写在菜单文字里（置灰的菜单项没有悬停提示）。逻辑见 [UnrealObjectReveal]。
 */
abstract class LuaRevealInUnrealAction(private val action: UnrealObjectReveal.Action) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun target(e: AnActionEvent): LuaV3Value? =
        XDebuggerTreeActionBase.getSelectedValue(e.dataContext) as? LuaV3Value

    override fun update(e: AnActionEvent) {
        val project = e.project
        val value = target(e)
        if (project == null || value?.ueObjectPath == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = true
        val problem = UnrealObjectReveal.target(project, value.session).problem
        e.presentation.isEnabled = problem == null
        e.presentation.text = if (problem == null) action.label else "${action.label}（$problem）"
        e.presentation.description = value.ueObjectPath
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val value = target(e) ?: return
        val path = value.ueObjectPath ?: return
        UnrealObjectReveal.reveal(project, value.session, path, value.ueObjectName, action)
    }
}

class LuaSelectInUnrealAction : LuaRevealInUnrealAction(UnrealObjectReveal.Action.SELECT)

class LuaOpenInUnrealAction : LuaRevealInUnrealAction(UnrealObjectReveal.Action.OPEN)

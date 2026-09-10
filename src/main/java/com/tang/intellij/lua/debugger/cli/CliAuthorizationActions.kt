package com.tang.intellij.lua.debugger.cli

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

private fun chooseTarget(event: AnActionEvent, service: CliGatewayApplicationService): String? {
    val targets = service.registry().targets()
    if (targets.isEmpty()) {
        Messages.showInfoMessage(event.project, "当前没有可授权的调试会话。", "EmmyLua CLI 授权")
        return null
    }
    val choices = targets.map { "${it.targetId} (${it.projectName})" }.toTypedArray()
    val selected = Messages.showEditableChooseDialog(
        "选择要授权的调试会话：", "EmmyLua CLI 授权", null, choices, choices.first(), null)
    return selected?.substringBefore(" (")
}

private fun askClient(event: AnActionEvent, title: String): String? =
    Messages.showInputDialog(event.project, "输入 CLI 客户端标识：", title, null, "emmy-debug", null)
        ?.trim()?.takeIf { it.isNotEmpty() }

class GrantCliDebugAccessAction : AnAction("授予 EmmyLua CLI 调试权限..."), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val service = CliGatewayApplicationService.getInstance()
        val targetId = chooseTarget(event, service) ?: return
        val clientId = askClient(event, "授予 EmmyLua CLI 调试权限") ?: return
        service.grant(targetId, clientId)
        Messages.showInfoMessage(event.project, "已授予 $clientId 访问 $targetId 的权限。", "EmmyLua CLI 授权")
    }
}

class RevokeCliDebugAccessAction : AnAction("撤销 EmmyLua CLI 调试权限..."), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val service = CliGatewayApplicationService.getInstance()
        val targetId = chooseTarget(event, service) ?: return
        val clientId = askClient(event, "撤销 EmmyLua CLI 调试权限") ?: return
        service.revoke(targetId, clientId)
        Messages.showInfoMessage(event.project, "已撤销 $clientId 对 $targetId 的权限。", "EmmyLua CLI 授权")
    }
}

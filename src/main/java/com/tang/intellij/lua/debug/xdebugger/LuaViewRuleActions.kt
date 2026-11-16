package com.tang.intellij.lua.debug.xdebugger

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase
import com.tang.intellij.lua.debug.engine.ViewRules
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import java.io.File

/**
 * 变量树右键「固定到顶部 / 取消固定」「隐藏此字段 / 取消隐藏」（variable-view.md 11.1、16）：
 * 改显示规则文件里这个类的 pin / hide，再推给所有会话并刷新变量树。
 * 只对有类名的对象的字符串字段出现；规则按类型生效，同类的所有实例一起变。
 */
abstract class LuaViewRuleAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    protected fun target(e: AnActionEvent): LuaV3Value? =
        XDebuggerTreeActionBase.getSelectedValue(e.dataContext) as? LuaV3Value

    override fun update(e: AnActionEvent) {
        val value = target(e)
        val rule = value?.ruleTarget()
        e.presentation.isEnabledAndVisible = e.project != null && rule != null
        if (value != null && rule != null) e.presentation.text = text(value, rule.first, rule.second)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (type, field) = target(e)?.ruleTarget() ?: return
        LuaViewRulesFile.edit(project) { rules -> toggle(rules, type, field) }
    }

    protected abstract fun text(value: LuaV3Value, type: String, field: String): String

    protected abstract fun toggle(rules: com.google.gson.JsonObject, type: String, field: String)
}

class LuaPinFieldAction : LuaViewRuleAction() {
    override fun text(value: LuaV3Value, type: String, field: String): String =
        if (value.pinned) LuaDebugBundle.message("variables.unpin", type) else LuaDebugBundle.message("variables.pin", type)

    override fun toggle(rules: com.google.gson.JsonObject, type: String, field: String) {
        ViewRules.togglePin(rules, type, field)
    }
}

class LuaHideFieldAction : LuaViewRuleAction() {
    override fun text(value: LuaV3Value, type: String, field: String): String = LuaDebugBundle.message("variables.hide", type)

    override fun toggle(rules: com.google.gson.JsonObject, type: String, field: String) {
        ViewRules.toggleHide(rules, type, field)
    }
}

/** 显示规则文件的读、改、写。写走 VFS（编辑器里开着的文件同步更新、可撤销）。 */
object LuaViewRulesFile {
    fun file(project: Project): File? {
        val path = LuaVariableViewSync.settings().rulesFile.trim().takeIf { it.isNotEmpty() } ?: return null
        return File(path).takeIf { it.isAbsolute } ?: File(project.basePath ?: return null, path)
    }

    fun edit(project: Project, change: (com.google.gson.JsonObject) -> Unit) {
        val file = file(project) ?: return notify(project, LuaDebugBundle.message("variables.rules.noFile"))
        val text = if (file.isFile) {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let { VfsUtil.loadText(it) } ?: file.readText(Charsets.UTF_8)
        } else {
            null
        }
        val rules = try {
            ViewRules.parse(text)
        } catch (e: IllegalArgumentException) {
            return notify(project, LuaDebugBundle.message("variables.rules.invalid", file.path, e.message ?: ""))
        }
        change(rules)
        val newText = ViewRules.format(rules)
        WriteCommandAction.runWriteCommandAction(project, LuaDebugBundle.message("variables.rules.command"), null, {
            val dir = VfsUtil.createDirectoryIfMissing(file.parentFile.path) ?: return@runWriteCommandAction
            val vf = dir.findChild(file.name) ?: dir.createChildData(this, file.name)
            VfsUtil.saveText(vf, newText)
        })
        // 保存后文件监听会再推一次；这里直接推，不等 VFS 事件，保证右键后立刻生效
        LuaVariableViewSync.pushRules(project, rules, rebuild = true)
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("EmmyLua Debug")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }
}

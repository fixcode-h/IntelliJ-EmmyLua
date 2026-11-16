package com.fixcode.intellij.lua.debug.xdebugger

import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

/**
 * 「诊断断点」（调试工具栏的更多菜单、Run 菜单）：列出本次会话加载过的 chunk、每个断点的匹配结果和建议的路径映射。
 * 报告同时打印到调试控制台，方便直接复制给我们；有建议映射时对话框里可以一键应用。
 */
class DiagnoseBreakpointsAction : DumbAwareAction(
    LuaDebugBundle.message("action.diagnoseBreakpoints"),
    LuaDebugBundle.message("action.diagnoseBreakpoints.description"),
    com.intellij.icons.AllIcons.Debugger.Db_muted_breakpoint,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun luaProcess(e: AnActionEvent): Pair<XDebugSession, LuaV3DebugProcess>? {
        val project = e.project ?: return null
        val session = e.getData(XDebugSession.DATA_KEY) ?: XDebuggerManager.getInstance(project).currentSession ?: return null
        val process = session.debugProcess as? LuaV3DebugProcess ?: return null
        return session to process
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = luaProcess(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (session, process) = luaProcess(e) ?: return
        object : Task.Backgroundable(project, LuaDebugBundle.message("action.diagnoseBreakpoints"), false) {
            private var report: Pair<String, Map<String, String>>? = null

            override fun run(indicator: ProgressIndicator) {
                report = process.diagnosticsReport()
            }

            override fun onSuccess() {
                val (text, suggested) = report ?: return
                session.consoleView?.print("[EmmyLua] 断点诊断\n$text\n", ConsoleViewContentType.LOG_INFO_OUTPUT)
                ReportDialog(project, text, suggested) { process.applyMappings(it) }.show()
            }
        }.queue()
    }

    private class ReportDialog(
        project: Project,
        private val text: String,
        private val suggested: Map<String, String>,
        private val apply: (Map<String, String>) -> Unit,
    ) : DialogWrapper(project, false) {
        init {
            title = LuaDebugBundle.message("action.diagnoseBreakpoints")
            setOKButtonText("关闭")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val area = JBTextArea(text).apply {
                isEditable = false
                lineWrap = false
                caretPosition = 0
            }
            return JBScrollPane(area).apply { preferredSize = JBUI.size(760, 420) }
        }

        override fun createActions(): Array<Action> {
            if (suggested.isEmpty()) return arrayOf(okAction)
            val applyAction = object : AbstractAction("应用建议的映射（${suggested.size}）") {
                override fun actionPerformed(e: ActionEvent?) {
                    apply(suggested)
                    close(OK_EXIT_CODE)
                }
            }
            return arrayOf(applyAction, okAction)
        }
    }
}

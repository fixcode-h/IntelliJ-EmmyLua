package com.fixcode.intellij.lua.onboarding

import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugProjectState
import com.fixcode.intellij.lua.onboarding.LuaOnboarding.Status
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.util.concurrent.TimeUnit
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 「EmmyLua 上手检查」：一页列出语言服务、UE 声明、UELink 后台生成、MCP 的状态和一键处理按钮。
 * 点了按钮马上重查一次，过几秒再查两次（安装、生成、服务器启动都在后台跑，要等一会儿才有结果）。
 */
class LuaOnboardingDialog private constructor(private val project: Project) : DialogWrapper(project, true) {
    private val content = JPanel(BorderLayout())
    @Volatile private var generation = 0

    private val recheckAction = object : DialogWrapperAction("重新检查") {
        override fun doAction(e: ActionEvent?) = refresh()
    }

    init {
        title = "EmmyLua 上手检查"
        setOKButtonText("关闭")
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(content).apply {
        border = JBUI.Borders.empty()
        preferredSize = JBUI.size(660, 480)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createLeftSideActions(): Array<Action> = arrayOf(recheckAction)

    private fun refresh() {
        val current = ++generation
        if (content.componentCount == 0) display(panel { row { label("正在检查…") } })
        val modality = ModalityState.stateForComponent(content)
        ApplicationManager.getApplication().executeOnPooledThread {
            val items = runCatching { LuaOnboarding.check(project) }.getOrElse {
                LOG.warn("上手检查失败", it)
                listOf(LuaOnboarding.Item("检查失败", Status.TODO, it.message ?: it.toString()))
            }
            ApplicationManager.getApplication().invokeLater({
                if (current == generation && !isDisposed) display(build(items))
            }, modality)
        }
    }

    private fun display(component: JComponent) {
        content.removeAll()
        content.add(component, BorderLayout.NORTH)
        content.revalidate()
        content.repaint()
    }

    private fun build(items: List<LuaOnboarding.Item>) = panel {
        val todo = items.count { it.status == Status.TODO }
        row {
            comment(
                if (todo == 0) "都准备好了。以后可以从「工具 → EmmyLua 上手检查」随时打开这一页。"
                else "还有 $todo 项待处理，点后面的按钮一键处理，处理完会自动刷新。"
            )
        }
        for (item in items) {
            separator()
            row {
                icon(iconOf(item.status))
                label(item.title).bold()
                label(textOf(item.status)).applyToComponent { foreground = colorOf(item.status) }
            }
            row { comment(StringUtil.escapeXmlEntities(item.detail)) }
            if (item.fixes.isNotEmpty()) {
                row {
                    for (fix in item.fixes) button(fix.label) { runFix(fix) }
                }
            }
        }
    }.apply { border = JBUI.Borders.empty(8, 12) }

    private fun runFix(fix: LuaOnboarding.Fix) {
        try {
            fix.run(project)
        } catch (e: Exception) {
            LOG.warn("上手检查：${fix.label} 失败", e)
        }
        if (isDisposed) return
        refresh()
        // 后台任务（安装 MCP、UE 生成声明、语言服务器启动）要过一会儿才有结果
        val modality = ModalityState.stateForComponent(content)
        for (delay in RECHECK_DELAYS_MS) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater({ if (!isDisposed) refresh() }, modality)
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun iconOf(status: Status) = when (status) {
        Status.OK -> AllIcons.General.InspectionsOK
        Status.TODO -> AllIcons.General.Warning
        Status.OPTIONAL -> AllIcons.General.Information
    }

    private fun textOf(status: Status) = when (status) {
        Status.OK -> "✓ 已就绪"
        Status.TODO -> "待处理"
        Status.OPTIONAL -> "可选"
    }

    private fun colorOf(status: Status) = when (status) {
        Status.OK -> JBColor(0x2E7D32, 0x6BB56B)
        Status.TODO -> JBColor(0xB26A00, 0xE0A040)
        Status.OPTIONAL -> JBColor.GRAY
    }

    companion object {
        private val LOG = logger<LuaOnboardingDialog>()
        private val RECHECK_DELAYS_MS = listOf(3000L, 10000L)

        /** 打开过就当用户已经看到了：打开 UE 项目时不再主动弹气泡。 */
        fun show(project: Project) {
            if (project.isDisposed) return
            LuaDebugProjectState.getInstance(project).onboardingDismissed = true
            LuaOnboardingDialog(project).show()
        }
    }
}

/** 工具 → EmmyLua 上手检查…：随时打开。 */
class OpenLuaOnboardingAction : DumbAwareAction(
    "EmmyLua 上手检查…",
    "一页查看语言服务、UE 声明、MCP 的状态，并一键处理",
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        LuaOnboardingDialog.show(e.project ?: return)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

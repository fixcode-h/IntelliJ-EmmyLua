package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.LuaBundle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.client.features.LSPProgressFeature
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressReport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * emmylua_ls 的后台进度在状态栏的显示。
 *
 * LSP4IJ 默认只把服务器的标题放进状态栏、阶段文字放进 text2（只在展开的后台任务列表里看得到），
 * 服务器不报百分比时就是来回滚的不确定进度条。这里：
 * - 标题、阶段文字换成本地化的，阶段和文件数写进状态栏那一行（indicator.text）；
 * - 「加载工作区」按上次实测用时估进度（[WorkspaceLoadEstimate]），每 250 毫秒刷新一次，结束时记下这次的用时；
 * - 「诊断工作区」服务器自己报百分比，照用，状态栏那一行也带上百分比。
 */
class EmmyLuaProgressFeature : LSPProgressFeature() {
    private class Load(val estimate: WorkspaceLoadEstimate, var ticker: ScheduledFuture<*>? = null)

    /** 按进度条认：同一个 token（加载工作区）在重新加载时会再开一次，各自一条。 */
    private val loads = ConcurrentHashMap<ProgressIndicator, Load>()

    override fun getProgressTaskTitle(title: String): String = super.getProgressTaskTitle(localizedTitle(title))

    override fun onProgressBegin(begin: WorkDoneProgressBegin, progressIndicator: ProgressIndicator) {
        if (begin.title == WorkspaceLoadEstimate.TITLE) startLoad(progressIndicator)
        super.onProgressBegin(begin, progressIndicator)
        loads[progressIndicator]?.let { refresh(progressIndicator, it) }
    }

    override fun onProgressReport(report: WorkDoneProgressReport, progressIndicator: ProgressIndicator) {
        val load = loads[progressIndicator]
        if (load == null) {
            super.onProgressReport(report, progressIndicator)
            report.percentage?.let { progressIndicator.text = "${serverName()}: ${LuaBundle.message("lsp.progress.diagnose.percent", it)}" }
            return
        }
        load.estimate.onMessage(report.message)
        super.onProgressReport(report, progressIndicator)
        if (load.estimate.phase == WorkspaceLoadEstimate.Phase.DONE) finishLoad(progressIndicator, load) else refresh(progressIndicator, load)
    }

    override fun updateMessage(message: String, progressIndicator: ProgressIndicator) {
        super.updateMessage(localizedMessage(message), progressIndicator)
    }

    private fun startLoad(indicator: ProgressIndicator) {
        val load = Load(WorkspaceLoadEstimate(readHistory()))
        loads[indicator] = load
        // 服务器在读文件、建索引时一声不吭，进度条得自己按时间往前走
        load.ticker = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (!indicator.isRunning || indicator.isCanceled) stopTicker(indicator, load) else refresh(indicator, load)
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun finishLoad(indicator: ProgressIndicator, load: Load) {
        stopTicker(indicator, load)
        load.estimate.measured()?.let { writeHistory(it.mergedWith(readHistory())) }
        indicator.isIndeterminate = false
        indicator.fraction = 1.0
        indicator.text = "${serverName()}: ${LuaBundle.message("lsp.progress.load.done")}"
    }

    private fun stopTicker(indicator: ProgressIndicator, load: Load) {
        load.ticker?.cancel(false)
        loads.remove(indicator, load)
    }

    private fun refresh(indicator: ProgressIndicator, load: Load) {
        val estimate = load.estimate
        val step = when (estimate.phase) {
            WorkspaceLoadEstimate.Phase.PREPARE -> LuaBundle.message("lsp.progress.load.prepare")
            WorkspaceLoadEstimate.Phase.COLLECT -> LuaBundle.message("lsp.progress.load.collect")
            WorkspaceLoadEstimate.Phase.INDEX -> estimate.files?.let { LuaBundle.message("lsp.progress.load.index", it) }
                ?: LuaBundle.message("lsp.progress.load.indexUnknown")
            WorkspaceLoadEstimate.Phase.DONE -> LuaBundle.message("lsp.progress.load.done")
        }
        val fraction = estimate.fraction()
        indicator.isIndeterminate = fraction == null
        val text = if (fraction == null) step else {
            indicator.fraction = fraction
            LuaBundle.message("lsp.progress.load.estimate", step, (fraction * 100).roundToInt())
        }
        indicator.text = "${serverName()}: ${LuaBundle.message("lsp.progress.load.title")} · $text"
    }

    private fun serverName(): String = serverDefinition.displayName

    private fun readHistory(): WorkspaceLoadEstimate.History? {
        val raw = runCatching { PropertiesComponent.getInstance(project).getValue(HISTORY_KEY) }.getOrNull() ?: return null
        val parts = raw.split(';')
        if (parts.size != 3) return null
        val collect = parts[0].toLongOrNull() ?: return null
        val perFile = parts[1].toDoubleOrNull() ?: return null
        val files = parts[2].toIntOrNull() ?: return null
        return WorkspaceLoadEstimate.History(collect, perFile, files)
    }

    private fun writeHistory(h: WorkspaceLoadEstimate.History) {
        runCatching { PropertiesComponent.getInstance(project).setValue(HISTORY_KEY, "${h.collectMs};${h.indexMsPerFile};${h.files}") }
    }

    companion object {
        private const val TICK_MS = 250L
        /** 存在项目的 workspace.xml 里：每台机器、每个项目的加载速度不一样，不进版本管理。 */
        private const val HISTORY_KEY = "emmylua.workspaceLoad.history"
        private val DIAGNOSTIC = Regex("""diagnostic (\d+)%""")
        private val INDEXING = Regex("""Indexing (\d+) files""")

        fun localizedTitle(title: String): String = when (title) {
            WorkspaceLoadEstimate.TITLE -> LuaBundle.message("lsp.progress.load.title")
            "Diagnose workspace" -> LuaBundle.message("lsp.progress.diagnose.title")
            "Refresh index" -> LuaBundle.message("lsp.progress.refresh.title")
            else -> title
        }

        fun localizedMessage(message: String): String {
            val m = message.trim()
            DIAGNOSTIC.matchEntire(m)?.let { return LuaBundle.message("lsp.progress.diagnose.percent", it.groupValues[1].toInt()) }
            INDEXING.matchEntire(m)?.let { return LuaBundle.message("lsp.progress.load.index", it.groupValues[1].toInt()) }
            return when (WorkspaceLoadEstimate.parse(m)) {
                WorkspaceLoadEstimate.Phase.PREPARE -> LuaBundle.message("lsp.progress.load.prepare")
                WorkspaceLoadEstimate.Phase.COLLECT -> LuaBundle.message("lsp.progress.load.collect")
                WorkspaceLoadEstimate.Phase.DONE -> LuaBundle.message("lsp.progress.load.done")
                else -> localizedTitle(m)
            }
        }
    }
}

package com.fixcode.intellij.lua.lsp

import kotlin.math.min

/**
 * emmylua_ls 的「Load workspace」进度只报阶段（`Collecting files`、`Indexing N files`），不报百分比：
 * 读文件和建索引在服务器里各是一整次调用，中间没有回调（上游 `handlers/initialized/mod.rs`）。
 * 所以按本项目上次加载的实测用时估一个进度：读文件按上次的时长，建索引按「上次每个文件的耗时 × 这次的文件数」。
 * 没有历史（第一次打开）时不估，保持不确定进度。纯逻辑，不依赖 IntelliJ，单测在 `WorkspaceLoadEstimateTest`。
 */
class WorkspaceLoadEstimate(private val history: History?, private val now: () -> Long = System::currentTimeMillis) {
    /** 上次加载的实测：读文件用时、建索引平均每个文件的用时、文件数。 */
    data class History(val collectMs: Long, val indexMsPerFile: Double, val files: Int) {
        /** 和旧记录各取一半：一次偶然的慢（杀毒软件扫盘、机器忙）不让下次的估计偏得太远。 */
        fun mergedWith(old: History?): History = if (old == null) this else History(
            collectMs = (collectMs + old.collectMs) / 2,
            indexMsPerFile = (indexMsPerFile + old.indexMsPerFile) / 2,
            files = files,
        )
    }

    enum class Phase { PREPARE, COLLECT, INDEX, DONE }

    private val startedAt = now()
    private var indexStartedAt = 0L
    private var doneAt = 0L

    var phase = Phase.PREPARE
        private set

    /** 要索引的文件数，服务器报了 `Indexing N files` 之后才有。 */
    var files: Int? = null
        private set

    /** 收到服务器的一条进度文字。认不出的不改阶段。 */
    fun onMessage(message: String?) {
        val phase = parse(message) ?: return
        when (phase) {
            Phase.INDEX -> if (this.phase != Phase.INDEX) {
                indexStartedAt = now()
                files = INDEXING.matchEntire(message!!.trim())?.groupValues?.get(1)?.toIntOrNull()
            }
            Phase.DONE -> if (this.phase != Phase.DONE) doneAt = now()
            else -> {}
        }
        if (phase.ordinal > this.phase.ordinal) this.phase = phase
    }

    /** 0–0.99；没有历史时 null（显示不确定进度）。估计超时也停在 99%，不倒退，真正结束由服务器的 end 决定。 */
    fun fraction(): Double? {
        val h = history ?: return null
        if (phase == Phase.DONE) return 1.0
        val t = now()
        val expectedCollect = h.collectMs.coerceAtLeast(1).toDouble()
        val f = if (phase == Phase.INDEX) {
            val collected = (indexStartedAt - startedAt).toDouble()
            val expectedIndex = (h.indexMsPerFile * (files ?: h.files)).coerceAtLeast(1.0)
            (collected + min((t - indexStartedAt).toDouble(), expectedIndex)) / (collected + expectedIndex)
        } else {
            // 读文件阶段还不知道这次的文件数，按上次的算；读得比上次慢时停在读文件那一段的末尾
            val total = expectedCollect + h.indexMsPerFile * h.files
            min((t - startedAt).toDouble(), expectedCollect) / total
        }
        return f.coerceIn(0.0, MAX)
    }

    /** 走完一次完整加载后的实测，存起来下次用。没走到建索引（0 个文件）或没结束时 null。 */
    fun measured(): History? {
        val n = files ?: return null
        if (phase != Phase.DONE || n <= 0) return null
        return History(
            collectMs = (indexStartedAt - startedAt).coerceAtLeast(0),
            indexMsPerFile = (doneAt - indexStartedAt).coerceAtLeast(0).toDouble() / n,
            files = n,
        )
    }

    companion object {
        /** begin 的标题，用它认出加载工作区这一项进度（上游 `context/status_bar.rs`）。 */
        const val TITLE = "Load workspace"
        const val MAX = 0.99
        private val INDEXING = Regex("""Indexing (\d+) files""")

        /** emmylua_ls 0.25 的几条阶段文字；升级时对着上游 `handlers/initialized/mod.rs` 核对。 */
        fun parse(message: String?): Phase? {
            val m = message?.trim() ?: return null
            return when {
                m == "Loading workspace files" -> Phase.PREPARE
                m == "Collecting files" -> Phase.COLLECT
                INDEXING.matches(m) -> Phase.INDEX
                m == "Finished loading workspace files" -> Phase.DONE
                else -> null
            }
        }
    }
}

package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.lsp.WorkspaceLoadEstimate.History
import com.fixcode.intellij.lua.lsp.WorkspaceLoadEstimate.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 阶段文字照 emmylua_ls 0.25 `handlers/initialized/mod.rs` 发出的原文。 */
class WorkspaceLoadEstimateTest {
    private var clock = 0L
    private fun estimate(history: History?) = WorkspaceLoadEstimate(history) { clock }

    @Test
    fun parsesServerPhases() {
        assertEquals(Phase.PREPARE, WorkspaceLoadEstimate.parse("Loading workspace files"))
        assertEquals(Phase.COLLECT, WorkspaceLoadEstimate.parse("Collecting files"))
        assertEquals(Phase.INDEX, WorkspaceLoadEstimate.parse("Indexing 1234 files"))
        assertEquals(Phase.DONE, WorkspaceLoadEstimate.parse("Finished loading workspace files"))
        assertNull(WorkspaceLoadEstimate.parse("diagnostic 12%"))
        assertNull(WorkspaceLoadEstimate.parse(null))
    }

    @Test
    fun noHistoryStaysIndeterminateButRecordsMeasurement() {
        val e = estimate(null)
        e.onMessage("Collecting files")
        clock = 2_000
        e.onMessage("Indexing 1000 files")
        assertNull(e.fraction())
        assertEquals(1000, e.files)
        clock = 7_000
        e.onMessage("Finished loading workspace files")
        assertEquals(History(collectMs = 2_000, indexMsPerFile = 5.0, files = 1000), e.measured())
    }

    @Test
    fun estimatesFromHistoryAndScalesByFileCount() {
        // 上次：读文件 2 秒，每个文件 5 毫秒，1000 个文件（建索引 5 秒）
        val e = estimate(History(2_000, 5.0, 1000))
        e.onMessage("Collecting files")
        clock = 1_000
        assertEquals(1_000.0 / 7_000, e.fraction()!!, 1e-9)
        clock = 5_000 // 读文件比上次慢：停在读文件那一段的末尾
        assertEquals(2_000.0 / 7_000, e.fraction()!!, 1e-9)
        e.onMessage("Indexing 2000 files") // 这次文件翻倍，建索引预计 10 秒
        clock = 10_000
        assertEquals((5_000.0 + 5_000) / (5_000 + 10_000), e.fraction()!!, 1e-9)
        clock = 60_000 // 超时不超过 99%
        assertEquals(WorkspaceLoadEstimate.MAX, e.fraction()!!, 1e-9)
        e.onMessage("Finished loading workspace files")
        assertEquals(1.0, e.fraction()!!, 1e-9)
    }

    @Test
    fun zeroFilesHasNoMeasurement() {
        val e = estimate(null)
        e.onMessage("Collecting files")
        e.onMessage("Finished loading workspace files")
        assertNull(e.measured())
    }

    @Test
    fun historyMergesHalfAndHalf() {
        val merged = History(4_000, 10.0, 500).mergedWith(History(2_000, 6.0, 400))
        assertEquals(History(3_000, 8.0, 500), merged)
    }
}

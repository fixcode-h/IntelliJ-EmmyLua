package com.fixcode.intellij.lua.debug.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakpointDiagnosticsTest {
    private fun bp(id: String, file: String, v: BreakpointVerification = BreakpointVerification.PENDING) =
        BreakpointView(id, BreakpointRequest(file, 10), v)

    private val runtimeReport = "VM 1：断点下发后还没有匹配上任何执行过的脚本。这段时间执行过的脚本 chunk 名（去重，共 2 个）：\n" +
        "  Game/UI/BagPanel.lua\n  Tutorials.BP_CharacterBase\n"

    @Test
    fun `没等够且 runtime 没报告时不下结论`() {
        val d = BreakpointDiagnostics(pendingTimeoutMillis = 6000)
        val b = bp("1", "D:/Proj/Content/Script/UI/BagPanel.lua")
        d.recordBreakpoint(b, now = 1000)
        assertNull(d.unmatchedReason(b, now = 3000))
        assertNotNull("超时后算没对上", d.unmatchedReason(b, now = 7001))
    }

    @Test
    fun `runtime 报告后马上下结论并给出同名 chunk`() {
        val d = BreakpointDiagnostics()
        val b = bp("1", "D:/Proj/Content/Script/UI/BagPanel.lua")
        d.recordBreakpoint(b, now = 1000)
        assertTrue(d.recordOutput("runtime", runtimeReport, 1, now = 1500))
        assertEquals(listOf("Game/UI/BagPanel.lua", "Tutorials.BP_CharacterBase"), d.chunks())
        val reason = d.unmatchedReason(b, now = 1600)!!
        assertTrue(reason, reason.contains("Game/UI/BagPanel.lua"))
    }

    @Test
    fun `报告之后才下的断点要重新等`() {
        val d = BreakpointDiagnostics()
        d.recordOutput("runtime", runtimeReport, 1, now = 1000)
        val b = bp("2", "D:/Proj/Content/Script/Other.lua")
        d.recordBreakpoint(b, now = 2000)
        assertNull(d.unmatchedReason(b, now = 2500))
    }

    @Test
    fun `生效的断点没有原因，不是诊断报告的输出不算`() {
        val d = BreakpointDiagnostics(pendingTimeoutMillis = 0)
        val ok = bp("1", "/a/B.lua", BreakpointVerification.VERIFIED)
        d.recordBreakpoint(ok, 0)
        assertNull(d.unmatchedReason(ok, 100))
        assertFalse(d.recordOutput("stdout", runtimeReport, 1))
        assertFalse(d.recordOutput("runtime", "hello", 1))
    }

    @Test
    fun `建议映射按共同后缀排序，路径已对上的不再建议`() {
        val file = "D:/Proj/Content/Script/UI/BagPanel.lua"
        val chunks = listOf("@X/BagPanel.lua", "Game/UI/BagPanel.lua", "Other.lua", "=[C]")
        assertEquals(listOf("Game/UI/BagPanel.lua", "@X/BagPanel.lua"), BreakpointDiagnostics.suggestChunks(file, chunks))

        val lines = listOf(BreakpointDiagnostics.Companion.BreakpointLine(bp("1", file), "没对上", listOf("Game/UI/BagPanel.lua")))
        val (text, suggested) = BreakpointDiagnostics.report(lines, chunks) { null }
        assertEquals(mapOf("Game/UI/BagPanel.lua" to file), suggested)
        assertTrue(text, text.contains("建议映射"))
        val (_, none) = BreakpointDiagnostics.report(lines, chunks) { SourcePaths.normalize(file) }
        assertTrue(none.isEmpty())
    }
    @Test
    fun `文件执行过、新断点还没执行到时不算路径没对上`() {
        // 用户在已经停过的文件里新加断点：同名 chunk 就是这个文件本身，不能把断点标灰
        val file = "D:/Proj/Content/Script/UI/BagPanel.lua"
        val d = BreakpointDiagnostics(pendingTimeoutMillis = 6000)
        d.recordChunk("UI/BagPanel.lua")
        val b = bp("1", file)
        d.recordBreakpoint(b, now = 1000)
        val mapping = { chunk: String -> if (chunk == "UI/BagPanel.lua") "d:/Proj/Content/Script/UI/BagPanel.lua" else null }
        assertTrue(BreakpointDiagnostics.mismatchedChunks(file, d.chunks(), mapping).isEmpty())
        val reason = d.unmatchedReason(b, now = 8000, mapping)!!
        assertFalse(reason, reason.contains("路径没对上"))

        // 同名 chunk 映射到别的文件（或没映射）时仍然算对不上
        assertEquals(listOf("UI/BagPanel.lua"), BreakpointDiagnostics.mismatchedChunks(file, d.chunks()) { "D:/Other/UI/BagPanel.lua" })
        assertTrue(d.unmatchedReason(b, now = 8000)!!.contains("路径没对上"))
    }
}

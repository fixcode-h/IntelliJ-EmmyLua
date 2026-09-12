package com.tang.intellij.lua.debugger.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for the CLI authorization dialog. Attach sessions embed
 * "(PID: n)" in the run configuration name, which becomes part of the target id;
 * the dialog must not truncate that id when resolving the selected entry.
 */
class CliAuthorizationTargetSelectionTest {
    private val attachTarget = CliTargetSummary(
        targetId = "emmy-4272665f-🎨[编辑器] SilverPalace [调试游戏] - 虚幻编辑器 (PID: 70636)-13d622cc-faa",
        projectName = "SilverPalace",
        state = "PAUSED",
        agentReady = true
    )

    private val cliTarget = CliTargetSummary(
        targetId = "emmy-11111111-run-abcdef123456",
        projectName = "Other",
        state = "RUNNING",
        agentReady = true
    )

    @Test
    fun `dialog choice round trips a target id that already contains parentheses`() {
        assertEquals(
            attachTarget.targetId,
            resolveAuthorizationTargetId(listOf(attachTarget), authorizationChoiceLabel(attachTarget))
        )
    }

    @Test
    fun `dialog choice resolves by index across multiple targets`() {
        val targets = listOf(cliTarget, attachTarget)
        assertEquals(
            attachTarget.targetId,
            resolveAuthorizationTargetId(targets, authorizationChoiceLabel(attachTarget))
        )
        assertEquals(
            cliTarget.targetId,
            resolveAuthorizationTargetId(targets, authorizationChoiceLabel(cliTarget))
        )
    }

    @Test
    fun `legacy truncated label resolves to the real target instead of a phantom id`() {
        assertEquals(
            attachTarget.targetId,
            resolveAuthorizationTargetId(listOf(attachTarget), attachTarget.targetId.substringBefore(" ("))
        )
    }

    @Test
    fun `a truncated label that is ambiguous keeps the typed value`() {
        val sibling = attachTarget.copy(targetId = "${attachTarget.targetId}-sibling")
        assertEquals(
            "emmy-4272665f",
            resolveAuthorizationTargetId(listOf(attachTarget, sibling), "emmy-4272665f")
        )
    }

    @Test
    fun `manually typed full target id is accepted`() {
        assertEquals(
            attachTarget.targetId,
            resolveAuthorizationTargetId(listOf(cliTarget, attachTarget), attachTarget.targetId)
        )
    }

    @Test
    fun `blank selection and empty target list resolve to null`() {
        assertNull(resolveAuthorizationTargetId(listOf(attachTarget), null))
        assertNull(resolveAuthorizationTargetId(listOf(attachTarget), "   "))
        assertNull(resolveAuthorizationTargetId(emptyList(), "anything"))
    }

    @Test
    fun `unknown value is preserved so a bad grant fails loudly`() {
        assertEquals(
            "no-such-target",
            resolveAuthorizationTargetId(listOf(cliTarget, attachTarget), "no-such-target")
        )
    }
}

package com.fixcode.intellij.lua.debug.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewRulesTest {
    @Test
    fun pinCreatesEntryInEmptyFile() {
        val rules = ViewRules.parse("")
        assertTrue(ViewRules.togglePin(rules, "ItemData", "id"))
        assertEquals("""{"version":1,"types":{"ItemData":{"pin":["id"]}}}""", rules.toString())
    }

    @Test
    fun pinAndHideAreExclusive() {
        val rules = ViewRules.parse("""{"types":{"Hero":{"hide":["hp"]}}}""")
        assertTrue(ViewRules.togglePin(rules, "Hero", "hp"))
        assertFalse(ViewRules.isHidden(rules, "Hero", "hp"))
        assertTrue(ViewRules.toggleHide(rules, "Hero", "hp"))
        assertFalse(ViewRules.isPinned(rules, "Hero", "hp"))
    }

    @Test
    fun unpinKeepsOtherFieldsAndDropsEmptyEntry() {
        val rules = ViewRules.parse("""{"version":1,"custom":true,"types":{"A":{"summary":"#{id}","pin":["id"]},"B":{"pin":["x"]}}}""")
        assertFalse(ViewRules.togglePin(rules, "A", "id"))
        assertFalse(ViewRules.togglePin(rules, "B", "x"))
        assertEquals("""{"version":1,"custom":true,"types":{"A":{"summary":"#{id}"}}}""", rules.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidJsonIsRejected() {
        ViewRules.parse("{ // 注释\n }")
    }
}

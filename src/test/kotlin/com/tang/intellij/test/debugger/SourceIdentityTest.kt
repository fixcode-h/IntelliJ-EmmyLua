package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.emmy.SourceIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIdentityTest {
    @Test
    fun `absolute paths discard parent traversal above root`() {
        assertEquals("/project/file.lua", SourceIdentity.normalizePath("/../project/dir/../../project/file.lua"))
        assertEquals("c:/project/file.lua", SourceIdentity.normalizePath("C:/../project/dir/../file.lua"))
    }

    @Test
    fun `relative paths retain leading parent traversal`() {
        assertEquals("../file.lua", SourceIdentity.normalizePath("dir/../../file.lua"))
    }
}

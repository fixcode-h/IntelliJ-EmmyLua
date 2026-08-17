package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.SourceMappingService
import com.tang.intellij.test.LuaTestBase

class SourceMappingServiceTest : LuaTestBase() {
    fun `test resolves absolute source path and caches positive result`() {
        val file = myFixture.configureByText("mapped.lua", "return 1")
        val service = SourceMappingService.getInstance(project)

        val first = service.resolveFile(file.virtualFile.path)
        val second = service.resolveFile(file.virtualFile.path.replace('/', '\\'))

        assertEquals(file.virtualFile, first)
        assertSame(first, second)
        assertEquals(0, service.createPosition(file.virtualFile.path, 1)!!.line)
    }

    fun `test display path is normalized`() {
        val service = SourceMappingService.getInstance(project)
        val base = project.basePath!!

        assertEquals("script/main.lua", service.displayPath("$base\\script\\main.lua"))
        assertEquals("external.lua", service.displayPath("C:\\other\\external.lua"))
    }
}

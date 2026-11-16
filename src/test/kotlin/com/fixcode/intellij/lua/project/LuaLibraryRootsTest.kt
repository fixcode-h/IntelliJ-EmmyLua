package com.fixcode.intellij.lua.project

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/** 额外库目录出现在「外部库」里，设置变化后刷新。 */
class LuaLibraryRootsTest : BasePlatformTestCase() {
    private lateinit var tmp: Path
    private lateinit var savedRoots: Array<String>
    private var savedDetect = true

    override fun setUp() {
        super.setUp()
        tmp = Files.createTempDirectory("lua-lib")
        val settings = LuaProjectSettings.getInstance(project)
        savedRoots = settings.additionalSourcesRoot
        savedDetect = settings.detectUnrealProject
        settings.detectUnrealProject = false
    }

    override fun tearDown() {
        try {
            val settings = LuaProjectSettings.getInstance(project)
            settings.additionalSourcesRoot = savedRoots
            settings.detectUnrealProject = savedDetect
            LuaLibraryRoots.getInstance(project).refresh()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            tmp.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test 额外库目录显示为外部库并能刷新`() {
        val lib = Files.createDirectories(tmp.resolve("decl"))
        Files.writeString(lib.resolve("UE.lua"), "---@class UE\nUE = {}\n")
        LuaProjectSettings.getInstance(project).additionalSourcesRoot = arrayOf(lib.toString())
        LuaLibraryRoots.getInstance(project).refresh()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val libs = LuaLibraryRoots.getInstance(project).libraries()
        assertEquals(listOf(LuaLibraryRoots.EXTRA_NAME), libs.map { it.presentableText })
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(lib.resolve("UE.lua"))!!
        assertTrue(ProjectFileIndex.getInstance(project).isInLibrary(file))

        LuaProjectSettings.getInstance(project).additionalSourcesRoot = emptyArray()
        LuaLibraryRoots.getInstance(project).refresh()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(LuaLibraryRoots.getInstance(project).libraries().isEmpty())
        assertFalse(ProjectFileIndex.getInstance(project).isInLibrary(file))
    }
}

package com.fixcode.intellij.lua.project

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceIgnoreDirsTest : TestCase() {
    private lateinit var tmp: Path

    override fun setUp() {
        super.setUp()
        tmp = Files.createTempDirectory("ws-ignore")
    }

    override fun tearDown() {
        tmp.toFile().deleteRecursively()
        super.tearDown()
    }

    private fun dirs(vararg paths: String) = paths.forEach { Files.createDirectories(tmp.resolve(it)) }

    fun `test prunes UE generated dirs but keeps declarations and libraries`() {
        Files.writeString(tmp.resolve("Game.uproject"), "{}")
        dirs(
            ".git/objects", "Binaries/Win64", "DerivedDataCache", "Saved/Logs", "Content/Script",
            "Intermediate/Build", "Intermediate/ProjectFiles", "Intermediate/UELink/LuaDecl", "Intermediate/IntelliSense",
            "Plugins/UnLua/Binaries", "Plugins/UnLua/Intermediate/Build", "Plugins/UnLua/Intermediate/IntelliSense",
            "Plugins/UnLua/Content/Script", "Saved/MyLua",
        )
        Files.writeString(tmp.resolve("Plugins/UnLua/UnLua.uplugin"), "{}")

        val result = WorkspaceIgnoreDirs.find(tmp, keep = listOf(tmp.resolve("Saved/MyLua")))
        assertEquals(
            listOf(
                ".git", "Binaries", "DerivedDataCache", "Intermediate/Build", "Intermediate/ProjectFiles",
                "Plugins/UnLua/Binaries", "Plugins/UnLua/Intermediate/Build",
            ),
            result,
        )
    }

    fun `test plain project only prunes tool dirs`() {
        dirs(".git", ".idea", "Binaries", "src")
        assertEquals(listOf(".git", ".idea"), WorkspaceIgnoreDirs.find(tmp, emptyList()))
    }

    fun `test UE dirs left alone when unreal detection is off`() {
        Files.writeString(tmp.resolve("Game.uproject"), "{}")
        dirs("Saved", "DerivedDataCache")
        assertEquals(emptyList<String>(), WorkspaceIgnoreDirs.find(tmp, emptyList(), unreal = false))
    }

    fun `test script folder inside UE project prunes nothing outside it`() {
        Files.writeString(tmp.resolve("Game.uproject"), "{}")
        dirs("Saved", "Content/Script/Game")
        assertEquals(emptyList<String>(), WorkspaceIgnoreDirs.find(tmp.resolve("Content/Script"), emptyList()))
    }
}

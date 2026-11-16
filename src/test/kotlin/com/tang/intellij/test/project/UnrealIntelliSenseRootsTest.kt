package com.tang.intellij.test.project

import com.tang.intellij.lua.project.UnrealIntelliSenseRoots
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

class UnrealIntelliSenseRootsTest : TestCase() {
    private lateinit var tmp: Path

    override fun setUp() {
        super.setUp()
        tmp = Files.createTempDirectory("ue-intellisense")
    }

    override fun tearDown() {
        tmp.toFile().deleteRecursively()
        super.tearDown()
    }

    fun `test finds unlua intellisense from Content Script`() {
        Files.writeString(tmp.resolve("Game.uproject"), "{}")
        val script = Files.createDirectories(tmp.resolve("Content/Script"))
        val unlua = Files.createDirectories(tmp.resolve("Plugins/UnLua/Intermediate/IntelliSense"))
        val nested = Files.createDirectories(tmp.resolve("Plugins/Scripting/UnLuaFork/Intermediate/IntelliSense"))
        Files.createDirectories(tmp.resolve("Plugins/Other/Source"))

        val roots = UnrealIntelliSenseRoots.find(script).toSet()
        assertEquals(setOf(unlua.toAbsolutePath().normalize(), nested.toAbsolutePath().normalize()), roots)
    }

    fun `test no uproject means no roots`() {
        val script = Files.createDirectories(tmp.resolve("Content/Script"))
        Files.createDirectories(tmp.resolve("Plugins/UnLua/Intermediate/IntelliSense"))
        assertTrue(UnrealIntelliSenseRoots.find(script).isEmpty())
    }
}

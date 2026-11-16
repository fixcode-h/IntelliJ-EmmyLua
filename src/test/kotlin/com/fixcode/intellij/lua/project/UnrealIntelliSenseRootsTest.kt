package com.fixcode.intellij.lua.project

import com.fixcode.intellij.lua.project.UnrealIntelliSenseRoots
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

    fun `test prefers uelink generated declarations over unlua`() {
        Files.writeString(tmp.resolve("Game.uproject"), "{}")
        val script = Files.createDirectories(tmp.resolve("Content/Script"))
        Files.createDirectories(tmp.resolve("Plugins/UnLua/Intermediate/IntelliSense"))
        val decl = Files.createDirectories(tmp.resolve("Intermediate/UELink/LuaDecl"))

        // 目录在但还没生成完（没有清单）时照旧用 UnLua 的
        assertEquals(1, UnrealIntelliSenseRoots.find(script).size)
        assertFalse(UnrealIntelliSenseRoots.find(script).contains(decl.toAbsolutePath().normalize()))

        Files.writeString(decl.resolve(".uelink-luadecl.json"), "{\"format\":1}")
        assertEquals(listOf(decl.toAbsolutePath().normalize()), UnrealIntelliSenseRoots.find(script))
    }

    fun `test parses uelink editor card`() {
        val card = tmp.resolve("123.json")
        Files.writeString(card, """{"pid":123,"kind":"editor","http":30110,"token":"t","startedAt":"2026-11-16T00:00:00Z"}""")
        assertEquals(UELinkClient.Instance(123, 30110, "t", "2026-11-16T00:00:00Z"), UELinkClient.parseCard(card))
        Files.writeString(card, """{"pid":123,"kind":"game","http":30110,"token":"t"}""")
        assertNull(UELinkClient.parseCard(card))
    }
}

package com.fixcode.intellij.lua.project

import com.fixcode.intellij.lua.debug.ui.process.UELinkProcessCards
import com.google.gson.JsonParser
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

class UELinkClientTest : TestCase() {
    private lateinit var tmp: Path

    override fun setUp() {
        super.setUp()
        tmp = Files.createTempDirectory("uelink")
    }

    override fun tearDown() {
        tmp.toFile().deleteRecursively()
        super.tearDown()
    }

    fun `test parses editor and game cards`() {
        val editor = UELinkClient.parseCardText(
            """{"pid": 12345, "kind": "editor", "project": "D:/Game/Game.uproject", "version": "2.5.1", "port": 30110,
               "http": 30111, "token": "t", "startedAt": "2026-11-14T02:00:00.000Z"}"""
        )!!
        assertEquals(12345L, editor.pid)
        assertEquals(30111, editor.port) // http 优先于旧字段 port
        assertEquals("Game", editor.projectName)
        assertEquals("UE 编辑器", editor.label)
        assertTrue(editor.hasHttp)

        val game = UELinkClient.parseCardText("""{"pid": 7, "kind": "game", "token": "t", "startedAt": "x"}""")!!
        assertEquals("game", game.kind)
        assertNull("-NoAgentPanel 启动时没有 HTTP", game.port)
        assertFalse(game.hasHttp)

        assertEquals("没有 kind 的旧名片当编辑器", "editor", UELinkClient.parseCardText("""{"pid": 1, "port": 1, "token": "t"}""")!!.kind)
        assertNull(UELinkClient.parseCardText("""{"kind": "editor"}"""))
        assertNull(UELinkClient.parseCardText("not json"))
    }

    fun `test find by pid and list only alive instances`() {
        val dir = Files.createDirectories(UELinkClient.instancesDir(tmp))
        val self = ProcessHandle.current().pid()
        Files.writeString(dir.resolve("$self.json"), """{"pid": $self, "kind": "game", "http": 1, "token": "t", "startedAt": "b"}""")
        Files.writeString(dir.resolve("999999999.json"), """{"pid": 999999999, "kind": "editor", "http": 2, "token": "t", "startedAt": "c"}""")
        assertEquals("game", UELinkClient.findByPid(tmp, self)!!.kind)
        assertNull(UELinkClient.findByPid(tmp, 42))
        // 名片留下但进程没了的跳过
        assertEquals(listOf(self), UELinkClient.listInstances(tmp).map { it.pid })
        assertNull("findEditor 只认编辑器", UELinkClient.findEditor(tmp))
    }

    fun `test module names relative to Content Script`() {
        val root = tmp
        val file = root.resolve("Content/Script/UI/Bag/BagPanel.lua")
        assertEquals("UI.Bag.BagPanel", UnrealLuaModules.moduleName(file, root))
        // 不知道 UE 根时从路径里找 Content/Script（大小写不一）
        assertEquals("Main", UnrealLuaModules.moduleName(Path.of("/x/Plugins/P/content/script/Main.lua"), null))
        assertEquals("Main", UnrealLuaModules.moduleName(Path.of("/x/Content/Script/Main.LUA"), null))
        assertNull(UnrealLuaModules.moduleName(root.resolve("Content/Other/A.lua"), root))
        assertNull(UnrealLuaModules.moduleName(root.resolve("Content/Script/A.txt"), root))
        assertNull("带点的目录名会歧义", UnrealLuaModules.moduleName(root.resolve("Content/Script/a.b/C.lua"), root))
    }

    fun `test reload targets skip background and put editor first`() {
        fun inst(pid: Long, kind: String, at: String, port: Int? = 1) = UELinkClient.Instance(pid, port, "t", at, kind)
        val targets = UnrealLuaModules.reloadTargets(listOf(
            inst(1, "game", "3"), inst(2, "editor", "1"), inst(3, "background", "4"), inst(4, "editor", "2"), inst(5, "game", "5", port = null),
        ))
        assertEquals(listOf(4L, 2L, 1L), targets.map { it.pid })
    }

    fun `test parse reload reply`() {
        val reply = UnrealLuaModules.parseReloadReply(JsonParser.parseString(
            """{"ok": true, "items": [{"module": "A", "ok": true, "mode": "require"}, {"module": "B", "ok": false, "error": "boom"},
               {"module": "C", "error": "x"}, {"oops": 1}], "note": "local 会重新初始化"}"""
        ).asJsonObject)
        assertEquals(listOf("A", "B", "C"), reply.items.map { it.module })
        assertEquals(listOf(true, false, false), reply.items.map { it.ok })
        assertEquals("require", reply.items[0].mode)
        assertEquals("local 会重新初始化", reply.note)
    }

    fun `test candidate roots from command line and binaries path`() {
        val proj = Files.createDirectories(tmp.resolve("Game"))
        Files.createDirectories(UELinkClient.instancesDir(proj))
        val packaged = Files.createDirectories(tmp.resolve("Pkg/Game"))
        Files.createDirectories(UELinkClient.instancesDir(packaged))
        val cmd = "\"C:/UE/UnrealEditor.exe\" \"${proj.toString().replace('\\', '/')}/Game.uproject\" -game"
        assertEquals(listOf(proj.toAbsolutePath()), UELinkProcessCards.candidateRoots(cmd, null, emptyList()))
        val exe = packaged.resolve("Binaries/Win64/Game.exe").toString()
        assertEquals(listOf(packaged), UELinkProcessCards.candidateRoots(null, exe, emptyList()))
        assertTrue(UELinkProcessCards.candidateRoots(null, "/usr/bin/x", emptyList()).isEmpty())
    }
}

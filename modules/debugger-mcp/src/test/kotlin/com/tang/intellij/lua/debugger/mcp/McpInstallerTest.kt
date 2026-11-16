package com.tang.intellij.lua.debugger.mcp

import com.tang.intellij.lua.debugger.mcp.install.McpClientKind
import com.tang.intellij.lua.debugger.mcp.install.McpInstaller
import com.tang.intellij.lua.debugger.mcp.install.VcsRoot
import com.tang.intellij.lua.debugger.mcp.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class McpInstallerTest {
    private lateinit var root: Path
    private lateinit var home: Path
    private lateinit var installer: McpInstaller
    private val url = McpInstaller.url(9330)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mcp-root")
        home = Files.createTempDirectory("mcp-home")
        installer = McpInstaller(home)
    }

    @After
    fun tearDown() {
        listOf(root, home).forEach { d -> d.toFile().deleteRecursively() }
    }

    private fun read(rel: String) = Files.readString(root.resolve(rel))

    @Test
    fun `新建 mcp-json`() {
        val r = installer.install(root, url, listOf(McpClientKind.CLAUDE_CODE)).single()
        assertTrue(r.success)
        assertTrue(r.created)
        val obj = Json.parseObject(read(".mcp.json"))
        val entry = (obj["mcpServers"] as Map<*, *>)["emmylua-debug"] as Map<*, *>
        assertEquals("http", entry["type"])
        assertEquals(url, entry["url"])
        assertTrue(read(".mcp.json").endsWith("\n"))
        assertTrue(installer.isInstalled(root, url))
    }

    @Test
    fun `合并不覆盖：保留其他服务器、用户字段和缩进`() {
        Files.writeString(root.resolve(".mcp.json"), """
            {
                "mcpServers": {
                    "uetovs": {
                        "type": "stdio",
                        "command": "cmd"
                    },
                    "emmylua-debug": {
                        "url": "http://127.0.0.1:1234/mcp",
                        "disabled": false
                    }
                }
            }
        """.trimIndent() + "\n")
        val status = installer.status(root, url).first { it.client == McpClientKind.CLAUDE_CODE }
        assertTrue(status.installed)
        assertTrue(status.outdated)
        assertFalse(installer.isInstalled(root, url))

        val r = installer.install(root, url, listOf(McpClientKind.CLAUDE_CODE)).single()
        assertTrue(r.message, r.success)
        val text = read(".mcp.json")
        assertTrue("应跟随 4 空格缩进：\n$text", text.contains("\n    \"mcpServers\""))
        val servers = Json.parseObject(text)["mcpServers"] as Map<*, *>
        assertEquals(listOf("uetovs", "emmylua-debug"), servers.keys.toList())
        val entry = servers["emmylua-debug"] as Map<*, *>
        assertEquals(url, entry["url"])
        assertEquals(false, entry["disabled"])
        assertTrue(installer.isInstalled(root, url))
        // 再装一次不改文件
        assertTrue(installer.install(root, url, listOf(McpClientKind.CLAUDE_CODE)).single().unchanged)
    }

    @Test
    fun `带注释的 JSONC 不写，给出手动片段`() {
        Files.createDirectories(root.resolve(".vscode"))
        val original = "{\n  // 我的配置\n  \"servers\": {}\n}\n"
        Files.writeString(root.resolve(".vscode/mcp.json"), original)
        val r = installer.install(root, url, listOf(McpClientKind.VSCODE)).single()
        assertFalse(r.success)
        assertNotNull(r.manualSnippet)
        assertTrue(r.manualSnippet!!.contains("\"servers\""))
        assertEquals(original, read(".vscode/mcp.json"))
    }

    @Test
    fun `各客户端的格式`() {
        installer.install(root, url, listOf(McpClientKind.CURSOR, McpClientKind.VSCODE, McpClientKind.GEMINI))
        assertEquals(url, ((Json.parseObject(read(".cursor/mcp.json"))["mcpServers"] as Map<*, *>)["emmylua-debug"] as Map<*, *>)["url"])
        val vs = (Json.parseObject(read(".vscode/mcp.json"))["servers"] as Map<*, *>)["emmylua-debug"] as Map<*, *>
        assertEquals("http", vs["type"])
        assertEquals(url, ((Json.parseObject(read(".gemini/settings.json"))["mcpServers"] as Map<*, *>)["emmylua-debug"] as Map<*, *>)["httpUrl"])
    }

    @Test
    fun `卸载只删自己那一项，删空了删文件`() {
        installer.install(root, url, listOf(McpClientKind.CLAUDE_CODE))
        assertTrue(installer.uninstall(root, listOf(McpClientKind.CLAUDE_CODE)).single().success)
        assertFalse(Files.exists(root.resolve(".mcp.json")))

        Files.createDirectories(root.resolve(".gemini"))
        Files.writeString(root.resolve(".gemini/settings.json"), "{\"theme\": \"dark\"}\n")
        installer.install(root, url, listOf(McpClientKind.GEMINI))
        installer.uninstall(root, listOf(McpClientKind.GEMINI))
        val obj = Json.parseObject(read(".gemini/settings.json"))
        assertEquals("dark", obj["theme"])
        assertTrue((obj["mcpServers"] as Map<*, *>).isEmpty())
    }

    @Test
    fun `Codex 的 toml 段新增、替换、删除`() {
        val file = home.resolve(".codex/config.toml")
        Files.createDirectories(file.parent)
        Files.writeString(file, "model = \"gpt\"\n\n[mcp_servers.other]\ncommand = \"x\"\n")
        installer.install(root, McpInstaller.url(1111), listOf(McpClientKind.CODEX))
        installer.install(root, url, listOf(McpClientKind.CODEX))
        val text = Files.readString(file)
        assertEquals(1, Regex("""\[mcp_servers\.emmylua-debug]""").findAll(text).count())
        assertTrue(text.contains("url = \"$url\""))
        assertTrue(text.contains("[mcp_servers.other]\ncommand = \"x\""))
        val status = installer.status(root, url).first { it.client == McpClientKind.CODEX }
        assertTrue(status.installed && !status.outdated)
        installer.uninstall(root, listOf(McpClientKind.CODEX))
        assertFalse(Files.readString(file).contains("emmylua-debug"))
        assertTrue(Files.readString(file).contains("model = \"gpt\""))
    }

    @Test
    fun `检测到的客户端默认勾选，没有时默认 Claude Code`() {
        assertEquals(listOf(McpClientKind.CLAUDE_CODE), installer.defaultSelection(root))
        Files.createDirectories(root.resolve(".cursor"))
        assertEquals(listOf(McpClientKind.CURSOR), installer.defaultSelection(root))
    }

    @Test
    fun `版本管理根目录：git、svn 最外层、p4config`() {
        val project = Files.createDirectories(root.resolve("game/Content/Script"))
        assertNull(VcsRoot.find(project, null).takeIf { it != null && it.startsWith(root) })
        Files.createDirectories(root.resolve("game/.git"))
        assertEquals(root.resolve("game"), VcsRoot.find(project, null))

        val svnRoot = Files.createTempDirectory("svn")
        try {
            val deep = Files.createDirectories(svnRoot.resolve("a/b"))
            Files.createDirectories(svnRoot.resolve(".svn"))
            Files.createDirectories(svnRoot.resolve("a/.svn"))
            Files.createDirectories(svnRoot.resolve("a/b/.svn"))
            assertEquals(svnRoot, VcsRoot.find(deep, null))
        } finally {
            svnRoot.toFile().deleteRecursively()
        }

        val p4 = Files.createTempDirectory("p4")
        try {
            val deep = Files.createDirectories(p4.resolve("x"))
            Files.writeString(p4.resolve("my.p4"), "P4CLIENT=a")
            assertEquals(p4, VcsRoot.find(deep, "my.p4"))
            // 没有标记时问 p4：client root 包含项目才用
            assertEquals(p4.toAbsolutePath(), VcsRoot.installRoot(deep) { p4 }.let { if (VcsRoot.find(deep) != null) p4.toAbsolutePath() else it })
        } finally {
            p4.toFile().deleteRecursively()
        }
    }
}

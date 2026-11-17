package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.install.McpClientKind
import com.fixcode.intellij.lua.debugger.mcp.install.McpInstaller
import com.fixcode.intellij.lua.debugger.mcp.install.McpServerEndpoint
import com.fixcode.intellij.lua.debugger.mcp.install.McpTransport
import com.fixcode.intellij.lua.debugger.mcp.install.VcsRoot
import com.fixcode.intellij.lua.debugger.mcp.json.Json
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

    /** 2026.1 起：Streamable HTTP，条目名按产品名。 */
    private val stream = McpServerEndpoint(McpInstaller.url(64342, McpTransport.STREAMABLE_HTTP), McpTransport.STREAMABLE_HTTP, "idea")
    /** 2025.x：只有 SSE，条目名 jetbrains。 */
    private val sse = McpServerEndpoint(McpInstaller.url(64342, McpTransport.SSE), McpTransport.SSE)

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

    private fun entry(rel: String, container: String, name: String) =
        (Json.parseObject(read(rel))[container] as Map<*, *>)[name] as Map<*, *>

    @Test
    fun `地址是 IDE MCP 服务器的`() {
        assertEquals("http://127.0.0.1:64342/stream", McpInstaller.url(64342, McpTransport.STREAMABLE_HTTP))
        assertEquals("http://127.0.0.1:64342/sse", McpInstaller.url(64342, McpTransport.SSE))
        assertEquals("jetbrains", sse.serverName)
        assertTrue(McpInstaller.sameUrl("http://localhost:64342/sse/", sse.url))
        assertTrue(McpInstaller.samePort("http://localhost:64342/sse", stream.url))
        assertFalse(McpInstaller.samePort("http://127.0.0.1:9330/mcp", stream.url))
    }

    @Test
    fun `新建 mcp-json`() {
        val r = installer.install(root, stream, listOf(McpClientKind.CLAUDE_CODE)).single()
        assertTrue(r.success)
        assertTrue(r.created)
        val e = entry(".mcp.json", "mcpServers", "idea")
        assertEquals("http", e["type"])
        assertEquals(stream.url, e["url"])
        assertNull(e["headers"])
        assertTrue(read(".mcp.json").endsWith("\n"))
        assertTrue(installer.isInstalled(root, stream))
        // IDE 换成只有 SSE 的版本（或端口变了）：算过期
        assertFalse(installer.isInstalled(root, stream.copy(url = McpInstaller.url(64343, McpTransport.STREAMABLE_HTTP))))
    }

    @Test
    fun `SSE 版本写 type sse`() {
        installer.install(root, sse, listOf(McpClientKind.CLAUDE_CODE, McpClientKind.VSCODE))
        assertEquals("sse", entry(".mcp.json", "mcpServers", "jetbrains")["type"])
        val vs = entry(".vscode/mcp.json", "servers", "jetbrains")
        assertEquals("sse", vs["type"])
        assertEquals(sse.url, vs["url"])
        // 已经写成 SSE、IDE 升级到有 Streamable HTTP 后：过期
        assertFalse(installer.isInstalled(root, sse.copy(transport = McpTransport.STREAMABLE_HTTP)))
    }

    @Test
    fun `合并不覆盖：保留其他服务器、用户字段和缩进；删掉旧版 emmylua-debug`() {
        Files.writeString(root.resolve(".mcp.json"), """
            {
                "mcpServers": {
                    "uetovs": {
                        "type": "stdio",
                        "command": "cmd"
                    },
                    "emmylua-debug": {
                        "type": "http",
                        "url": "http://127.0.0.1:9330/mcp"
                    },
                    "idea": {
                        "type": "sse",
                        "url": "http://127.0.0.1:1234/sse",
                        "disabled": false
                    }
                }
            }
        """.trimIndent() + "\n")
        val status = installer.status(root, stream).first { it.client == McpClientKind.CLAUDE_CODE }
        assertTrue(status.installed)
        assertTrue(status.outdated)
        assertTrue(status.legacy)
        assertFalse(installer.isInstalled(root, stream))

        val r = installer.install(root, stream, listOf(McpClientKind.CLAUDE_CODE)).single()
        assertTrue(r.message, r.success)
        val text = read(".mcp.json")
        assertTrue("应跟随 4 空格缩进：\n$text", text.contains("\n    \"mcpServers\""))
        val servers = Json.parseObject(text)["mcpServers"] as Map<*, *>
        assertEquals(listOf("uetovs", "idea"), servers.keys.toList())
        val e = servers["idea"] as Map<*, *>
        assertEquals(stream.url, e["url"])
        assertEquals("http", e["type"])
        assertEquals(false, e["disabled"])
        assertTrue(installer.isInstalled(root, stream))
        // 再装一次不改文件
        assertTrue(installer.install(root, stream, listOf(McpClientKind.CLAUDE_CODE)).single().unchanged)
    }

    @Test
    fun `IDE 改用产品名后，指向同一服务器的 jetbrains 项删掉，别的 IDE 的留着`() {
        Files.writeString(root.resolve(".mcp.json"), """{"mcpServers":{"jetbrains":{"type":"sse","url":"http://localhost:64342/sse"}}}""")
        installer.install(root, stream, listOf(McpClientKind.CLAUDE_CODE))
        assertEquals(listOf("idea"), (Json.parseObject(read(".mcp.json"))["mcpServers"] as Map<*, *>).keys.toList())

        Files.writeString(root.resolve(".mcp.json"), """{"mcpServers":{"jetbrains":{"type":"sse","url":"http://localhost:64400/sse"}}}""")
        installer.install(root, stream, listOf(McpClientKind.CLAUDE_CODE))
        assertEquals(listOf("jetbrains", "idea"), (Json.parseObject(read(".mcp.json"))["mcpServers"] as Map<*, *>).keys.toList())
    }

    @Test
    fun `带注释的 JSONC 不写，给出手动片段`() {
        Files.createDirectories(root.resolve(".vscode"))
        val original = "{\n  // 我的配置\n  \"servers\": {}\n}\n"
        Files.writeString(root.resolve(".vscode/mcp.json"), original)
        val r = installer.install(root, stream, listOf(McpClientKind.VSCODE)).single()
        assertFalse(r.success)
        assertNotNull(r.manualSnippet)
        assertTrue(r.manualSnippet!!.contains("\"servers\""))
        assertTrue(r.manualSnippet!!.contains(stream.url))
        assertEquals(original, read(".vscode/mcp.json"))
    }

    @Test
    fun `各客户端的格式`() {
        installer.install(root, stream, listOf(McpClientKind.CURSOR, McpClientKind.VSCODE, McpClientKind.GEMINI))
        assertEquals(stream.url, entry(".cursor/mcp.json", "mcpServers", "idea")["url"])
        val vs = entry(".vscode/mcp.json", "servers", "idea")
        assertEquals("http", vs["type"])
        assertEquals(stream.url, entry(".gemini/settings.json", "mcpServers", "idea")["httpUrl"])
        assertTrue(installer.status(root, stream).filter { it.client.projectLevel && it.client != McpClientKind.CLAUDE_CODE }.all { it.installed && !it.outdated })

        // Gemini 在 SSE 版本用 url，换成 Streamable HTTP 后 httpUrl 取代 url
        installer.install(root, sse.copy(serverName = "idea"), listOf(McpClientKind.GEMINI))
        val g = entry(".gemini/settings.json", "mcpServers", "idea")
        assertEquals(sse.url, g["url"])
        assertNull(g["httpUrl"])
    }

    @Test
    fun `要令牌的 IDE：写成从环境变量展开，不写令牌本身`() {
        val tok = stream.copy(authHeader = "IJ_MCP_AUTH_TOKEN")
        installer.install(root, tok, listOf(McpClientKind.CLAUDE_CODE, McpClientKind.VSCODE, McpClientKind.CODEX))
        assertEquals(mapOf("IJ_MCP_AUTH_TOKEN" to "\${IJ_MCP_AUTH_TOKEN}"), entry(".mcp.json", "mcpServers", "idea")["headers"])
        assertEquals(mapOf("IJ_MCP_AUTH_TOKEN" to "\${env:IJ_MCP_AUTH_TOKEN}"), entry(".vscode/mcp.json", "servers", "idea")["headers"])
        assertTrue(Files.readString(home.resolve(".codex/config.toml")).contains("env_http_headers = { \"IJ_MCP_AUTH_TOKEN\" = \"IJ_MCP_AUTH_TOKEN\" }"))
        assertTrue(installer.isInstalled(root, tok))
        // 不要令牌的版本：headers 去掉
        installer.install(root, stream, listOf(McpClientKind.CLAUDE_CODE))
        assertNull(entry(".mcp.json", "mcpServers", "idea")["headers"])
    }

    @Test
    fun `卸载只删自己那一项，删空了删文件`() {
        installer.install(root, stream, listOf(McpClientKind.CLAUDE_CODE))
        assertTrue(installer.uninstall(root, stream, listOf(McpClientKind.CLAUDE_CODE)).single().success)
        assertFalse(Files.exists(root.resolve(".mcp.json")))

        Files.createDirectories(root.resolve(".gemini"))
        Files.writeString(root.resolve(".gemini/settings.json"), "{\"theme\": \"dark\"}\n")
        installer.install(root, stream, listOf(McpClientKind.GEMINI))
        installer.uninstall(root, stream, listOf(McpClientKind.GEMINI))
        val obj = Json.parseObject(read(".gemini/settings.json"))
        assertEquals("dark", obj["theme"])
        assertTrue((obj["mcpServers"] as Map<*, *>).isEmpty())
    }

    @Test
    fun `Codex 的 toml 段新增、替换、删除，旧版段删掉`() {
        val file = home.resolve(".codex/config.toml")
        Files.createDirectories(file.parent)
        Files.writeString(file, "model = \"gpt\"\n\n[mcp_servers.emmylua-debug]\nurl = \"http://127.0.0.1:9330/mcp\"\n\n[mcp_servers.other]\ncommand = \"x\"\n")
        installer.install(root, stream.copy(url = McpInstaller.url(1111, McpTransport.STREAMABLE_HTTP)), listOf(McpClientKind.CODEX))
        installer.install(root, stream, listOf(McpClientKind.CODEX))
        val text = Files.readString(file)
        assertEquals(1, Regex("""\[mcp_servers\.idea]""").findAll(text).count())
        assertFalse(text.contains("emmylua-debug"))
        assertTrue(text.contains("url = \"${stream.url}\""))
        assertTrue(text.contains("[mcp_servers.other]\ncommand = \"x\""))
        val status = installer.status(root, stream).first { it.client == McpClientKind.CODEX }
        assertTrue(status.installed && !status.outdated)
        installer.uninstall(root, stream, listOf(McpClientKind.CODEX))
        assertFalse(Files.readString(file).contains("mcp_servers.idea"))
        assertTrue(Files.readString(file).contains("model = \"gpt\""))
    }

    @Test
    fun `Codex 不支持 SSE：不写，说明怎么办`() {
        val r = installer.install(root, sse, listOf(McpClientKind.CODEX)).single()
        assertFalse(r.success)
        assertTrue(r.message!!.contains("Streamable HTTP"))
        assertFalse(Files.exists(home.resolve(".codex/config.toml")))
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

    @Test
    fun `IDE 打开 UE 项目的脚本目录：装到版本管理根或 UE 项目根`() {
        val ue = Files.createTempDirectory("ue")
        try {
            val script = Files.createDirectories(ue.resolve("Game/Content/Script"))
            Files.writeString(ue.resolve("Game/Game.uproject"), "{}")
            // 没有版本管理：UE 项目根（含 .uproject），而不是打开的 Content/Script
            assertEquals(ue.resolve("Game"), VcsRoot.installRoot(script))
            assertEquals(ue.resolve("Game"), VcsRoot.findUnrealProject(script))

            // 脚本目录自己是个仓库（子模块），UE 项目没有版本管理：仍以 UE 项目根为准
            Files.writeString(script.resolve(".git"), "gitdir: ../../.git/modules/Script")
            assertEquals(ue.resolve("Game"), VcsRoot.installRoot(script))

            // UE 项目在仓库的子目录里：装到仓库根
            Files.createDirectories(ue.resolve(".git"))
            assertEquals(ue, VcsRoot.installRoot(script))
            Files.delete(script.resolve(".git"))
            assertEquals(ue, VcsRoot.installRoot(script))
        } finally {
            ue.toFile().deleteRecursively()
        }

        // 不是 UE 项目、也没有版本管理：项目目录本身
        val plain = Files.createTempDirectory("plain")
        try {
            val lua = Files.createDirectories(plain.resolve("lua"))
            assertEquals(lua, VcsRoot.installRoot(lua))
        } finally {
            plain.toFile().deleteRecursively()
        }
    }
}

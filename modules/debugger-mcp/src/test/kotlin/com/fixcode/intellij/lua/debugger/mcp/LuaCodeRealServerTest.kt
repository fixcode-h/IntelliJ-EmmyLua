package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.code.CodeLocation
import com.fixcode.intellij.lua.debugger.mcp.code.DocSymbol
import com.fixcode.intellij.lua.debugger.mcp.code.LuaCodeBackend
import com.fixcode.intellij.lua.debugger.mcp.code.LuaCodeTools
import com.fixcode.intellij.lua.debugger.mcp.code.LuaFile
import com.fixcode.intellij.lua.debugger.mcp.code.ServerState
import com.fixcode.intellij.lua.debugger.mcp.code.WorkspaceSymbolHit
import com.fixcode.intellij.lua.debugger.mcp.json.Json
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpCallLog
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpToolDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * 用真的 emmylua_ls 跑 `lua_code`：只在环境变量 `EMMYLUA_LS` 指向可执行文件时运行（CI 和平时跳过），
 * 用来核对服务器回包的形状（全名 `M:Foo`、selectionRange、workspace/symbol 只有全局和类型）和工具的整形是否对得上。
 *
 * 跑法：`EMMYLUA_LS=/path/to/emmylua_ls ./gradlew :modules:debugger-mcp:test --tests '*LuaCodeRealServerTest*'`
 */
class LuaCodeRealServerTest {
    private lateinit var root: Path
    private lateinit var server: StdioLsp
    private lateinit var client: McpTestClient
    private val T = LuaCodeTools.NAME

    @Before
    fun setUp() {
        val exe = System.getenv("EMMYLUA_LS")
        assumeTrue("没有设置 EMMYLUA_LS，跳过真服务器测试", !exe.isNullOrBlank() && Files.isExecutable(Path.of(exe)))
        root = Files.createTempDirectory("lua-code-real").toRealPath()
        write("Content/Script/UI/MainPanel.lua", FakeCodeBackend.MAIN_PANEL)
        write("Content/Script/Weapon/Gun.lua", FakeCodeBackend.GUN)
        server = StdioLsp(exe!!, root)
        val backend = RealBackend(root, server)
        // 等服务器把工作区索引完：documentSymbol 有结果为止
        val main = backend.luaFiles().first { it.path.endsWith("MainPanel.lua") }
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && backend.documentSymbols(main).get(10, TimeUnit.SECONDS).isNullOrEmpty()) Thread.sleep(200)
        client = McpTestClient(McpToolDispatcher(LuaCodeTools({ listOf(backend) }).all(), McpCallLog()))
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
        if (::root.isInitialized) root.toFile().deleteRecursively()
    }

    private fun write(rel: String, text: String) {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        p.writeText(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(o: McpTestClient.ToolOutcome) = o["items"] as List<Map<String, Any?>>

    @Test
    fun `真服务器：search outline files references`() {
        val obc = client.call(T, mapOf("action" to "search", "query" to "OBC"))
        println("search OBC -> ${obc.raw}")
        assertFalse(obc.raw, obc.isError)
        val m = items(obc)[0]
        assertEquals("OnButtonClicked", m["name"]); assertEquals("method", m["kind"]); assertEquals("M", m["container"])
        assertEquals(7L, m["line"]); assertEquals(12L, m["col"]); assertEquals("UI.MainPanel", m["module"])

        val show = client.call(T, mapOf("action" to "search", "query" to "UI.MainPanel.Show"))
        println("search UI.MainPanel.Show -> ${show.raw}")
        assertEquals("Show", items(show).single()["name"])

        val cls = client.call(T, mapOf("action" to "search", "query" to "MainPanel", "kinds" to listOf("class")))
        println("search class MainPanel -> ${cls.raw}")
        assertEquals("MainPanel", items(cls)[0]["name"])

        val outline = client.call(T, mapOf("action" to "outline", "path" to "UI.MainPanel"))
        println("outline -> ${outline.raw}")
        assertEquals(
            listOf("MainPanel", "Title", "M", "helper", "OnButtonClicked", "Show", "localFn", "GlobalHelper"),
            items(outline).map { it["name"] },
        )

        val files = client.call(T, mapOf("action" to "files", "query" to "Weapon/Gun"))
        println("files -> ${files.raw}")
        assertEquals("Weapon.Gun", items(files)[0]["module"])

        val refs = client.call(T, mapOf("action" to "references", "query" to "GlobalHelper"))
        println("references GlobalHelper -> ${refs.raw}")
        assertFalse(refs.raw, refs.isError)
        assertTrue(refs.raw, items(refs).any { it["text"] == "GlobalHelper(1, 2)" })

        val fire = client.call(T, mapOf("action" to "references", "file" to "Weapon/Gun.lua", "line" to 2, "query" to "Fire"))
        println("references Fire -> ${fire.raw}")
        assertFalse(fire.raw, fire.isError)
    }

    /** 直接读工作区目录的后端，LSP 请求发给真的 emmylua_ls。 */
    private class RealBackend(private val root: Path, private val lsp: StdioLsp) : LuaCodeBackend {
        override val projectName = "real"
        override fun serverState() = ServerState.Ready
        override fun luaFiles(): List<LuaFile> = Files.walk(root).use { s ->
            s.filter { it.isRegularFile() && it.extension == "lua" }.map { LuaFile(it.toString(), false, Files.getLastModifiedTime(it).toMillis()) }.toList()
        }
        override fun baseDirs() = listOf(root.toString(), root.resolve("Content/Script").toString())
        override fun fileAt(path: String) = Path.of(path).takeIf { it.isRegularFile() }?.let { LuaFile(it.toString()) }
        override fun owns(path: String) = Path.of(path).startsWith(root)
        override fun readText(file: LuaFile) = runCatching { Files.readString(Path.of(file.path)) }.getOrNull()

        override fun documentSymbols(file: LuaFile): CompletableFuture<List<DocSymbol>?> =
            lsp.request("textDocument/documentSymbol", mapOf("textDocument" to mapOf("uri" to uri(file.path)))).thenApply { r ->
                (r as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::docSymbol) }
            }

        override fun workspaceSymbols(query: String): CompletableFuture<List<WorkspaceSymbolHit>> =
            lsp.request("workspace/symbol", mapOf("query" to query)).thenApply { r ->
                (r as? List<*>).orEmpty().mapNotNull { e ->
                    val m = e as? Map<*, *> ?: return@mapNotNull null
                    val loc = m["location"] as Map<*, *>
                    val (line, col) = start(loc["range"] as Map<*, *>)
                    WorkspaceSymbolHit(m["name"] as String, (m["kind"] as Number).toInt(), m["containerName"] as? String, path(loc["uri"] as String), line, col)
                }
            }

        override fun references(file: LuaFile, line: Int, col: Int, includeDeclaration: Boolean): CompletableFuture<List<CodeLocation>> =
            lsp.request("textDocument/references", mapOf(
                "textDocument" to mapOf("uri" to uri(file.path)),
                "position" to mapOf("line" to line, "character" to col),
                "context" to mapOf("includeDeclaration" to includeDeclaration),
            )).thenApply { r ->
                (r as? List<*>).orEmpty().map { e ->
                    val m = e as Map<*, *>
                    val (l, c) = start(m["range"] as Map<*, *>)
                    CodeLocation(path(m["uri"] as String), l, c)
                }
            }

        private fun docSymbol(m: Map<*, *>): DocSymbol {
            val (line, col) = start((m["selectionRange"] ?: m["range"]) as Map<*, *>)
            return DocSymbol(
                m["name"] as String, (m["kind"] as Number).toInt(), m["detail"] as? String, line, col,
                (m["children"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::docSymbol) },
            )
        }

        private fun start(range: Map<*, *>): Pair<Int, Int> {
            val s = range["start"] as Map<*, *>
            return (s["line"] as Number).toInt() to (s["character"] as Number).toInt()
        }

        private fun uri(path: String) = Path.of(path).toUri().toString()
        private fun path(uri: String) = Path.of(URI(uri)).toString()
    }

    /** 最小的 stdio LSP 客户端：只够 initialize、发请求、回服务器的 workspace/configuration。 */
    private class StdioLsp(exe: String, root: Path) {
        private val proc = ProcessBuilder(exe).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val pending = ConcurrentHashMap<Long, CompletableFuture<Any?>>()
        private val ids = AtomicInteger()

        init {
            Thread({ readLoop() }, "lsp-reader").apply { isDaemon = true }.start()
            request("initialize", mapOf(
                "processId" to null,
                "rootUri" to root.toUri().toString(),
                "workspaceFolders" to listOf(mapOf("uri" to root.toUri().toString(), "name" to "w")),
                "capabilities" to mapOf(
                    "workspace" to mapOf("configuration" to true),
                    "textDocument" to mapOf("documentSymbol" to mapOf("hierarchicalDocumentSymbolSupport" to true)),
                ),
            )).get(30, TimeUnit.SECONDS)
            send(mapOf("jsonrpc" to "2.0", "method" to "initialized", "params" to emptyMap<String, Any?>()))
        }

        fun request(method: String, params: Any?): CompletableFuture<Any?> {
            val id = ids.incrementAndGet().toLong()
            val f = CompletableFuture<Any?>()
            pending[id] = f
            send(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
            return f
        }

        @Synchronized
        private fun send(msg: Map<String, Any?>) {
            val body = Json.write(msg).toByteArray(Charsets.UTF_8)
            proc.outputStream.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            proc.outputStream.write(body)
            proc.outputStream.flush()
        }

        private fun readLoop() {
            val input = BufferedInputStream(proc.inputStream)
            while (true) {
                var length = -1
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                }
                val buf = input.readNBytes(length)
                val msg = Json.parseObject(String(buf, Charsets.UTF_8))
                val id = (msg["id"] as? Number)?.toLong()
                if (msg["method"] != null && id != null) {
                    // 服务器发来的请求：configuration 回空配置，其余回 null
                    val result: Any? = if (msg["method"] == "workspace/configuration") {
                        ((msg["params"] as Map<*, *>)["items"] as List<*>).map { emptyMap<String, Any?>() }
                    } else null
                    send(mapOf("jsonrpc" to "2.0", "id" to msg["id"], "result" to result))
                } else if (id != null) {
                    val f = pending.remove(id) ?: continue
                    if (msg["error"] != null) f.completeExceptionally(RuntimeException(Json.write(msg["error"]))) else f.complete(msg["result"])
                }
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return out.toString(Charsets.US_ASCII).trimEnd('\r')
                out.write(b)
            }
        }

        fun close() {
            runCatching { request("shutdown", null).get(5, TimeUnit.SECONDS) }
            runCatching { send(mapOf("jsonrpc" to "2.0", "method" to "exit")) }
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }
}

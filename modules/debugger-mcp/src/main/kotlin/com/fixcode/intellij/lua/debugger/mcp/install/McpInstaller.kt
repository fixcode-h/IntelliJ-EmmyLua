package com.fixcode.intellij.lua.debugger.mcp.install

import com.fixcode.intellij.lua.debugger.mcp.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 支持写入配置的 MCP 客户端。名字和 IDE 侧 LuaMcpClient 一一对应。 */
enum class McpClientKind(val displayName: String, val projectLevel: Boolean) {
    CLAUDE_CODE("Claude Code", true),
    CURSOR("Cursor", true),
    VSCODE("VS Code / Copilot", true),
    GEMINI("Gemini CLI", true),
    CODEX("Codex", false),
}

/** IDE 自带 MCP 服务器提供的传输：2025.2–2025.3 只有 SSE（`/sse`），2026.1 起加了 Streamable HTTP（`/stream`）。 */
enum class McpTransport { STREAMABLE_HTTP, SSE }

/**
 * 写进客户端配置的服务器：IDE 自带 MCP 服务器的地址。
 *
 * [serverName] 照 IDE 自己「自动配置」写的条目名（2025.x 是 `jetbrains`，2026.1 起按产品名，如 `idea`、`rider`），
 * 这样用户再点 IDE 的自动配置也只是更新同一项，客户端里不会出现两份同样的工具。
 * [authHeader] 不为空时（IDE 要求全局服务器带令牌的版本），配置里写这个请求头，值是让客户端从同名环境变量展开的占位符，
 * 不把令牌本身写进文件（项目级配置会提交到仓库）。
 */
data class McpServerEndpoint(
    val url: String,
    val transport: McpTransport,
    val serverName: String = McpInstaller.DEFAULT_SERVER_NAME,
    val authHeader: String? = null,
)

data class McpClientConfigStatus(
    val client: McpClientKind,
    val configFile: Path,
    val detected: Boolean,
    val installed: Boolean,
    /** 已安装但地址、传输和 IDE 现在的不一致，或者还留着旧版插件自己服务的 `emmylua-debug` 项。 */
    val outdated: Boolean = false,
    /** 配置里现在写的地址。 */
    val currentUrl: String? = null,
    /** 还有旧版（插件自己的 9330 服务）留下的 `emmylua-debug` 项，安装时会删掉。 */
    val legacy: Boolean = false,
    val error: String? = null,
)

data class McpInstallOutcome(
    val client: McpClientKind,
    val configFile: Path,
    val success: Boolean,
    val message: String? = null,
    val manualSnippet: String? = null,
    val created: Boolean = false,
    /** 文件内容没变（已经是最新）。 */
    val unchanged: Boolean = false,
)

/**
 * 每个客户端一个写入器，格式变了只改对应的那个（各客户端的配置格式以官方文档为准）。
 *
 * 规则：合并不覆盖，只增改 [McpServerEndpoint.serverName] 这一项，顺手删掉旧版的 `emmylua-debug`、
 * 和指向同一个服务器的 IDE 旧条目名；其余原样保留。解析失败不写，给出手动粘贴的片段；
 * 配置里只有本机回环地址，没有密钥，可以安全提交到仓库。
 */
interface McpClientConfigWriter {
    val client: McpClientKind

    /** [root] 是安装位置（见 [VcsRoot.installRoot]），[home] 是用户目录（只有用户级配置用到）。 */
    fun configFile(root: Path, home: Path): Path

    /** 这个客户端看起来在用（对应目录存在），安装对话框默认勾选。 */
    fun detect(root: Path, home: Path): Boolean

    fun status(root: Path, home: Path, endpoint: McpServerEndpoint): McpClientConfigStatus

    fun install(root: Path, home: Path, endpoint: McpServerEndpoint): McpInstallOutcome

    fun uninstall(root: Path, home: Path, endpoint: McpServerEndpoint): McpInstallOutcome

    /** 解析失败时让用户手动粘贴的片段。 */
    fun snippet(endpoint: McpServerEndpoint): String
}

/**
 * JSON 配置（Claude Code / Cursor / VS Code / Gemini）：`{ <containerKey>: { "<serverName>": {...} } }`。
 */
open class JsonMcpConfigWriter(
    override val client: McpClientKind,
    private val relativePath: String,
    private val containerKey: String,
    /** 存在则认为客户端在用的目录或文件（相对根目录）。 */
    private val detectPaths: List<String>,
    /** 这个客户端认的、存地址的键（Gemini 的 Streamable HTTP 用 httpUrl）。 */
    private val urlKeys: List<String> = listOf("url"),
    /** 我们管理的字段：重写时先删掉这些，再写 [entry] 给的，用户加的其他字段（例如 disabled）保留。 */
    private val managedKeys: List<String> = listOf("type", "url", "httpUrl", "headers", "command", "args"),
    /** 请求头里令牌占位符的写法：客户端各自的环境变量展开语法。 */
    private val tokenPlaceholder: (header: String) -> String = { "\${$it}" },
    private val entry: (endpoint: McpServerEndpoint) -> Map<String, Any?>,
) : McpClientConfigWriter {

    override fun configFile(root: Path, home: Path): Path = root.resolve(relativePath)

    override fun detect(root: Path, home: Path): Boolean =
        detectPaths.any { Files.exists(root.resolve(it)) }

    private fun fullEntry(endpoint: McpServerEndpoint): Map<String, Any?> {
        val e = LinkedHashMap(entry(endpoint))
        endpoint.authHeader?.let { h -> e["headers"] = linkedMapOf(h to tokenPlaceholder(h)) }
        return e
    }

    override fun snippet(endpoint: McpServerEndpoint): String =
        Json.pretty(linkedMapOf(containerKey to linkedMapOf(endpoint.serverName to fullEntry(endpoint))))

    private fun urlOf(server: Map<*, *>?): String? = server?.let { s -> urlKeys.firstNotNullOfOrNull { s[it] as? String } ?: s["url"] as? String ?: s["httpUrl"] as? String }

    override fun status(root: Path, home: Path, endpoint: McpServerEndpoint): McpClientConfigStatus {
        val file = configFile(root, home)
        val detected = detect(root, home)
        if (!Files.isRegularFile(file)) return McpClientConfigStatus(client, file, detected, installed = false)
        return try {
            val obj = Json.parseObject(readText(file))
            val container = obj[containerKey] as? Map<*, *>
            val server = container?.get(endpoint.serverName) as? Map<*, *>
            val legacy = container?.containsKey(McpInstaller.LEGACY_SERVER_NAME) == true
            val current = urlOf(server)
            val wanted = fullEntry(endpoint)
            val sameShape = server != null && wanted.all { (k, v) ->
                if (k in urlKeys || k == "url" || k == "httpUrl") McpInstaller.sameUrl(server[k] as? String, v as? String) else server[k] == v
            }
            McpClientConfigStatus(
                client, file, detected = true,
                installed = server != null,
                outdated = (server != null && !sameShape) || legacy,
                currentUrl = current,
                legacy = legacy,
            )
        } catch (e: Exception) {
            McpClientConfigStatus(client, file, detected = true, installed = false, error = "无法解析 ${file.fileName}：${e.message}")
        }
    }

    override fun install(root: Path, home: Path, endpoint: McpServerEndpoint): McpInstallOutcome {
        val file = configFile(root, home)
        val existed = Files.isRegularFile(file)
        val original = if (existed) readTextOrNull(file) ?: return fail(file, "读不了 $file", endpoint) else null
        val obj: MutableMap<String, Any?> = if (original.isNullOrBlank()) linkedMapOf() else try {
            Json.parseObject(original)
        } catch (e: Exception) {
            return fail(file, "${file.fileName} 不是严格 JSON（可能有注释或尾逗号），为避免改坏没有写入：${e.message}", endpoint)
        }
        val container = when (val c = obj[containerKey]) {
            null -> linkedMapOf<String, Any?>().also { obj[containerKey] = it }
            is MutableMap<*, *> -> @Suppress("UNCHECKED_CAST") (c as MutableMap<String, Any?>)
            else -> return fail(file, "${file.fileName} 里的 \"$containerKey\" 不是对象，没有写入", endpoint)
        }
        // 保留用户在这一项里加的其他字段（例如 disabled），只覆盖我们管理的字段
        val merged = linkedMapOf<String, Any?>()
        (container[endpoint.serverName] as? Map<*, *>)?.forEach { (k, v) -> if (k.toString() !in managedKeys) merged[k.toString()] = v }
        merged.putAll(fullEntry(endpoint))
        container[endpoint.serverName] = merged
        removeStale(container, endpoint)
        val text = McpInstaller.formatLike(original, Json.pretty(obj, if (original != null) Json.detectIndent(original) else "  "))
        if (text == original) return McpInstallOutcome(client, file, true, "已是最新", unchanged = true)
        return try {
            McpInstaller.writeAtomically(file, text)
            McpInstallOutcome(client, file, true, if (existed) "已更新" else "已创建", created = !existed)
        } catch (e: IOException) {
            fail(file, "写入 $file 失败：${e.message}", endpoint)
        }
    }

    /** 删掉旧版插件服务的 `emmylua-debug`，以及 IDE 旧条目名（`jetbrains`）里指向同一个服务器的那项，免得工具出现两份。 */
    private fun removeStale(container: MutableMap<String, Any?>, endpoint: McpServerEndpoint) {
        container.remove(McpInstaller.LEGACY_SERVER_NAME)
        val old = McpInstaller.DEFAULT_SERVER_NAME
        if (old != endpoint.serverName && McpInstaller.samePort(urlOf(container[old] as? Map<*, *>), endpoint.url)) container.remove(old)
    }

    override fun uninstall(root: Path, home: Path, endpoint: McpServerEndpoint): McpInstallOutcome {
        val file = configFile(root, home)
        if (!Files.isRegularFile(file)) return McpInstallOutcome(client, file, true, "未安装", unchanged = true)
        val original = readTextOrNull(file) ?: return McpInstallOutcome(client, file, false, "读不了 $file")
        val obj = try {
            Json.parseObject(original)
        } catch (e: Exception) {
            return McpInstallOutcome(client, file, false, "${file.fileName} 解析失败，请手动删掉 \"${endpoint.serverName}\" 一项：${e.message}")
        }
        @Suppress("UNCHECKED_CAST")
        val container = obj[containerKey] as? MutableMap<String, Any?>
        if (container == null) return McpInstallOutcome(client, file, true, "未安装", unchanged = true)
        val before = container.size
        container.remove(endpoint.serverName)
        container.remove(McpInstaller.LEGACY_SERVER_NAME)
        if (container.size == before) return McpInstallOutcome(client, file, true, "未安装", unchanged = true)
        return try {
            if (container.isEmpty() && obj.size == 1) {
                // 文件里只剩我们写的空壳，删掉，免得留下没用的文件
                Files.delete(file)
                McpInstallOutcome(client, file, true, "已删除配置文件")
            } else {
                McpInstaller.writeAtomically(file, McpInstaller.formatLike(original, Json.pretty(obj, Json.detectIndent(original))))
                McpInstallOutcome(client, file, true, "已移除")
            }
        } catch (e: IOException) {
            McpInstallOutcome(client, file, false, "写入 $file 失败：${e.message}")
        }
    }

    private fun fail(file: Path, message: String, endpoint: McpServerEndpoint) =
        McpInstallOutcome(client, file, false, message, manualSnippet = snippet(endpoint))
}

/**
 * Codex：用户级 `~/.codex/config.toml`。只做最小的 TOML 段替换，不引 TOML 库：
 * 找到 `[mcp_servers.<serverName>]` 段就替换段内容，没有就追加到文件末尾；旧版的 `[mcp_servers.emmylua-debug]` 段删掉。
 * Codex 只支持 Streamable HTTP（和 stdio），IDE 只有 SSE 时（2025.x）不写，让用户用 IDE 的自动配置。
 */
class CodexConfigWriter : McpClientConfigWriter {
    override val client = McpClientKind.CODEX

    override fun configFile(root: Path, home: Path): Path = home.resolve(".codex").resolve("config.toml")

    override fun detect(root: Path, home: Path): Boolean = Files.isDirectory(home.resolve(".codex"))

    private fun body(endpoint: McpServerEndpoint): List<String> = buildList {
        add("[mcp_servers.${endpoint.serverName}]")
        add("url = \"${escape(endpoint.url)}\"")
        // Codex 不展开 http_headers 里的变量：令牌头用 env_http_headers 让它从同名环境变量读（和 IDE 的写法一致）
        endpoint.authHeader?.let { add("env_http_headers = { \"${escape(it)}\" = \"${escape(it)}\" }") }
    }

    override fun snippet(endpoint: McpServerEndpoint): String = body(endpoint).joinToString("\n") + "\n"

    override fun status(root: Path, home: Path, endpoint: McpServerEndpoint): McpClientConfigStatus {
        val file = configFile(root, home)
        val detected = detect(root, home)
        if (!Files.isRegularFile(file)) return McpClientConfigStatus(client, file, detected, installed = false)
        val text = readTextOrNull(file)
            ?: return McpClientConfigStatus(client, file, detected, false, error = "读不了 $file")
        val lines = text.lines()
        val legacy = findSection(lines, McpInstaller.LEGACY_SERVER_NAME) != null
        val section = findSection(lines, endpoint.serverName)
            ?: return McpClientConfigStatus(client, file, detected, installed = false, outdated = legacy, legacy = legacy)
        val current = lines.subList(section.first + 1, section.last + 1)
            .firstNotNullOfOrNull { URL_LINE.matchEntire(it.trim())?.groupValues?.get(1) }
        return McpClientConfigStatus(
            client, file, detected, true,
            outdated = !McpInstaller.sameUrl(current, endpoint.url) || legacy, currentUrl = current, legacy = legacy,
        )
    }

    override fun install(root: Path, home: Path, endpoint: McpServerEndpoint): McpInstallOutcome {
        val file = configFile(root, home)
        if (endpoint.transport != McpTransport.STREAMABLE_HTTP) {
            return McpInstallOutcome(
                client, file, false,
                "Codex 只支持 Streamable HTTP，这个版本的 IDE 只提供 SSE（IDE 2026.1 起才有 Streamable HTTP）。" +
                    "请用「设置 → 工具 → MCP 服务器」里的自动配置给 Codex 配 stdio 方式。",
            )
        }
        val existed = Files.isRegularFile(file)
        val original = if (existed) readTextOrNull(file)
            ?: return McpInstallOutcome(client, file, false, "读不了 $file", snippet(endpoint)) else ""
        val eol = if (original.contains("\r\n")) "\r\n" else "\n"
        val lines = original.lines().toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.size - 1)
        removeSection(lines, McpInstaller.LEGACY_SERVER_NAME)
        val body = body(endpoint)
        val section = findSection(lines, endpoint.serverName)
        if (section != null) {
            // 保留段里我们不管的键（例如 enabled、startup_timeout_sec）
            val kept = lines.subList(section.first + 1, section.last + 1).filter { line ->
                val t = line.trim()
                t.isNotBlank() && MANAGED_KEYS.none { k -> Regex("""^"?${Regex.escape(k)}"?\s*=.*""").matches(t) }
            }
            // 段后面原有的空行还在 section.last 之后，原样保留
            val rebuilt = lines.subList(0, section.first) + body + kept + lines.subList(section.last + 1, lines.size)
            lines.clear()
            lines.addAll(rebuilt)
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += body
        }
        val text = lines.joinToString(eol).trimEnd() + eol
        if (text == original) return McpInstallOutcome(client, file, true, "已是最新", unchanged = true)
        return try {
            McpInstaller.writeAtomically(file, text)
            McpInstallOutcome(client, file, true, if (existed) "已更新（全局配置）" else "已创建（全局配置）", created = !existed)
        } catch (e: IOException) {
            McpInstallOutcome(client, file, false, "写入 $file 失败：${e.message}", snippet(endpoint))
        }
    }

    override fun uninstall(root: Path, home: Path, endpoint: McpServerEndpoint): McpInstallOutcome {
        val file = configFile(root, home)
        if (!Files.isRegularFile(file)) return McpInstallOutcome(client, file, true, "未安装", unchanged = true)
        val original = readTextOrNull(file) ?: return McpInstallOutcome(client, file, false, "读不了 $file")
        val eol = if (original.contains("\r\n")) "\r\n" else "\n"
        val lines = original.lines().toMutableList()
        val a = removeSection(lines, endpoint.serverName)
        val b = removeSection(lines, McpInstaller.LEGACY_SERVER_NAME)
        if (!a && !b) return McpInstallOutcome(client, file, true, "未安装", unchanged = true)
        return try {
            McpInstaller.writeAtomically(file, lines.joinToString(eol).trimEnd() + eol)
            McpInstallOutcome(client, file, true, "已移除")
        } catch (e: IOException) {
            McpInstallOutcome(client, file, false, "写入 $file 失败：${e.message}")
        }
    }

    private fun removeSection(lines: MutableList<String>, name: String): Boolean {
        val section = findSection(lines, name) ?: return false
        repeat(section.last - section.first + 1) { lines.removeAt(section.first) }
        // 去掉删段后留下的连续空行
        if (section.first < lines.size && lines[section.first].isBlank() && (section.first == 0 || lines[section.first - 1].isBlank())) {
            lines.removeAt(section.first)
        }
        return true
    }

    /** 段的行范围（含表头，到下一个表头前的最后一个非空行）。 */
    private fun findSection(lines: List<String>, name: String): IntRange? {
        val header = Regex("""\[\s*mcp_servers\.(?:"${Regex.escape(name)}"|${Regex.escape(name)})\s*]\s*(#.*)?""")
        val start = lines.indexOfFirst { header.matches(it.trim()) }
        if (start < 0) return null
        var end = start
        for (i in start + 1 until lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("[")) break
            if (t.isNotEmpty()) end = i
        }
        return start..end
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private val URL_LINE = Regex("""url\s*=\s*"([^"]*)"\s*(#.*)?""")
        private val MANAGED_KEYS = listOf("url", "command", "args", "env_http_headers", "http_headers")
    }
}

/** 根据客户端选择写入器，统一处理多个客户端。 */
class McpInstaller(
    private val home: Path = Path.of(System.getProperty("user.home")),
    val writers: List<McpClientConfigWriter> = defaultWriters(),
) {
    fun writer(client: McpClientKind): McpClientConfigWriter = writers.first { it.client == client }

    fun status(root: Path, endpoint: McpServerEndpoint): List<McpClientConfigStatus> =
        writers.map { w ->
            runCatching { w.status(root, home, endpoint) }.getOrElse { e ->
                McpClientConfigStatus(w.client, w.configFile(root, home), w.detect(root, home), false, error = e.message)
            }
        }

    fun install(root: Path, endpoint: McpServerEndpoint, clients: Collection<McpClientKind>): List<McpInstallOutcome> =
        clients.map { c -> writer(c).install(root, home, endpoint) }

    fun uninstall(root: Path, endpoint: McpServerEndpoint, clients: Collection<McpClientKind>): List<McpInstallOutcome> =
        clients.map { c -> writer(c).uninstall(root, home, endpoint) }

    /** 「已安装」：任一项目级客户端的配置里有 IDE 服务器这一项，且地址指向 IDE 现在的端口和传输。 */
    fun isInstalled(root: Path, endpoint: McpServerEndpoint): Boolean =
        status(root, endpoint).any { it.client.projectLevel && it.installed && !it.outdated }

    /** 默认勾选：已检测到的客户端；一个都没有时默认 Claude Code。 */
    fun defaultSelection(root: Path): List<McpClientKind> {
        val detected = writers.filter { it.client.projectLevel && it.detect(root, home) }.map { it.client }
        return detected.ifEmpty { listOf(McpClientKind.CLAUDE_CODE) }
    }

    companion object {
        /** IDE 2025.x「自动配置」写的条目名；2026.1 起 IDE 改用产品名，由 IDE 侧读出来放进 [McpServerEndpoint.serverName]。 */
        const val DEFAULT_SERVER_NAME = "jetbrains"

        /** 旧版插件自己开的 MCP 服务（127.0.0.1:9330/mcp）的条目名；那个服务已经没有了，安装时删掉。 */
        const val LEGACY_SERVER_NAME = "emmylua-debug"

        /** IDE MCP 服务器的默认端口（McpServerSettings.DEFAULT_MCP_PORT，2025.2–2026.3 一致）。 */
        const val DEFAULT_IDE_PORT = 64342

        fun url(port: Int, transport: McpTransport): String =
            "http://127.0.0.1:$port" + if (transport == McpTransport.STREAMABLE_HTTP) "/stream" else "/sse"

        private fun transportType(endpoint: McpServerEndpoint) =
            if (endpoint.transport == McpTransport.STREAMABLE_HTTP) "http" else "sse"

        fun defaultWriters(): List<McpClientConfigWriter> = listOf(
            JsonMcpConfigWriter(
                McpClientKind.CLAUDE_CODE, ".mcp.json", "mcpServers",
                detectPaths = listOf(".mcp.json", ".claude", "CLAUDE.md"),
            ) { e -> linkedMapOf("type" to transportType(e), "url" to e.url) },
            JsonMcpConfigWriter(
                McpClientKind.CURSOR, ".cursor/mcp.json", "mcpServers",
                detectPaths = listOf(".cursor", ".cursorrules"),
                tokenPlaceholder = { "\${env:$it}" },
            ) { e -> linkedMapOf("url" to e.url) },
            JsonMcpConfigWriter(
                McpClientKind.VSCODE, ".vscode/mcp.json", "servers",
                detectPaths = listOf(".vscode"),
                tokenPlaceholder = { "\${env:$it}" },
            ) { e -> linkedMapOf("type" to transportType(e), "url" to e.url) },
            // Gemini CLI：Streamable HTTP 用 httpUrl，SSE 用 url
            JsonMcpConfigWriter(
                McpClientKind.GEMINI, ".gemini/settings.json", "mcpServers",
                detectPaths = listOf(".gemini", "GEMINI.md"),
                urlKeys = listOf("httpUrl", "url"),
            ) { e ->
                if (e.transport == McpTransport.STREAMABLE_HTTP) linkedMapOf("httpUrl" to e.url) else linkedMapOf("url" to e.url)
            },
            CodexConfigWriter(),
        )

        /** localhost 和 127.0.0.1、末尾斜杠视为同一地址。 */
        fun sameUrl(a: String?, b: String?): Boolean {
            fun norm(s: String?) = s?.trim()?.trimEnd('/')?.replace("://localhost:", "://127.0.0.1:")
            return a != null && norm(a) == norm(b)
        }

        /** 两个本机地址指向同一个端口（不管路径是 /sse 还是 /stream）。 */
        fun samePort(a: String?, b: String?): Boolean {
            fun port(s: String?) = s?.let { Regex("""^https?://(?:127\.0\.0\.1|localhost|\[::1]):(\d+)(?:/|$)""").find(it.trim())?.groupValues?.get(1) }
            val pa = port(a)
            return pa != null && pa == port(b)
        }

        /** 跟随原文件的换行符和结尾换行；新文件用 \n 结尾。 */
        fun formatLike(original: String?, text: String): String {
            val eol = if (original?.contains("\r\n") == true) "\r\n" else "\n"
            val body = if (eol == "\n") text else text.replace("\n", eol)
            val trailing = original == null || original.endsWith("\n") || original.isBlank()
            return if (trailing) body + eol else body
        }

        fun writeAtomically(file: Path, text: String) {
            file.parent?.let { Files.createDirectories(it) }
            val tmp = Files.createTempFile(file.parent ?: file.toAbsolutePath().parent, ".${file.fileName}", ".tmp")
            try {
                Files.writeString(tmp, text)
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }
}

private fun readTextOrNull(file: Path): String? = try {
    readText(file)
} catch (e: IOException) {
    null
}

private fun readText(file: Path): String = String(Files.readAllBytes(file), Charsets.UTF_8).removePrefix("﻿")

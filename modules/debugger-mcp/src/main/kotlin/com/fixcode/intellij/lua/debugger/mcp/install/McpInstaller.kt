package com.fixcode.intellij.lua.debugger.mcp.install

import com.fixcode.intellij.lua.debugger.mcp.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 支持写入配置的 MCP 客户端（设计文档 6.5）。名字和 IDE 侧 LuaMcpClient 一一对应。 */
enum class McpClientKind(val displayName: String, val projectLevel: Boolean) {
    CLAUDE_CODE("Claude Code", true),
    CURSOR("Cursor", true),
    VSCODE("VS Code / Copilot", true),
    GEMINI("Gemini CLI", true),
    CODEX("Codex", false),
}

data class McpClientConfigStatus(
    val client: McpClientKind,
    val configFile: Path,
    val detected: Boolean,
    val installed: Boolean,
    val outdated: Boolean = false,
    /** 配置里现在写的地址。 */
    val currentUrl: String? = null,
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
 * 每个客户端一个写入器，格式变了只改对应的那个（设计文档 6.5「各客户端的配置格式以官方文档为准」）。
 *
 * 规则：合并不覆盖，只增改 [McpInstaller.SERVER_NAME] 这一项；解析失败不写，给出手动粘贴的片段；
 * 配置里只有本机回环地址，没有密钥，可以安全提交到仓库。
 */
interface McpClientConfigWriter {
    val client: McpClientKind

    /** [root] 是版本管理根目录，[home] 是用户目录（只有用户级配置用到）。 */
    fun configFile(root: Path, home: Path): Path

    /** 这个客户端看起来在用（对应目录存在），安装对话框默认勾选。 */
    fun detect(root: Path, home: Path): Boolean

    fun status(root: Path, home: Path, url: String): McpClientConfigStatus

    fun install(root: Path, home: Path, url: String): McpInstallOutcome

    fun uninstall(root: Path, home: Path): McpInstallOutcome

    /** 解析失败时让用户手动粘贴的片段。 */
    fun snippet(url: String): String
}

/**
 * JSON 配置（Claude Code / Cursor / VS Code / Gemini）：`{ <containerKey>: { "emmylua-debug": {...} } }`。
 */
open class JsonMcpConfigWriter(
    override val client: McpClientKind,
    private val relativePath: String,
    private val containerKey: String,
    /** 存在则认为客户端在用的目录或文件（相对根目录）。 */
    private val detectPaths: List<String>,
    /** 本条目里存地址的键。 */
    private val urlKey: String,
    private val entry: (url: String) -> Map<String, Any?>,
) : McpClientConfigWriter {

    override fun configFile(root: Path, home: Path): Path = root.resolve(relativePath)

    override fun detect(root: Path, home: Path): Boolean =
        detectPaths.any { Files.exists(root.resolve(it)) }

    override fun snippet(url: String): String =
        Json.pretty(linkedMapOf(containerKey to linkedMapOf(McpInstaller.SERVER_NAME to entry(url))))

    override fun status(root: Path, home: Path, url: String): McpClientConfigStatus {
        val file = configFile(root, home)
        val detected = detect(root, home)
        if (!Files.isRegularFile(file)) return McpClientConfigStatus(client, file, detected, installed = false)
        return try {
            val obj = Json.parseObject(readText(file))
            val server = (obj[containerKey] as? Map<*, *>)?.get(McpInstaller.SERVER_NAME) as? Map<*, *>
            val current = server?.let { it[urlKey] as? String ?: it["url"] as? String }
            McpClientConfigStatus(
                client, file, detected = true,
                installed = server != null,
                outdated = server != null && !McpInstaller.sameUrl(current, url),
                currentUrl = current,
            )
        } catch (e: Exception) {
            McpClientConfigStatus(client, file, detected = true, installed = false, error = "无法解析 ${file.fileName}：${e.message}")
        }
    }

    override fun install(root: Path, home: Path, url: String): McpInstallOutcome {
        val file = configFile(root, home)
        val existed = Files.isRegularFile(file)
        val original = if (existed) readTextOrNull(file) ?: return fail(file, "读不了 $file", url) else null
        val obj: MutableMap<String, Any?> = if (original.isNullOrBlank()) linkedMapOf() else try {
            Json.parseObject(original)
        } catch (e: Exception) {
            return fail(file, "${file.fileName} 不是严格 JSON（可能有注释或尾逗号），为避免改坏没有写入：${e.message}", url)
        }
        val container = when (val c = obj[containerKey]) {
            null -> linkedMapOf<String, Any?>().also { obj[containerKey] = it }
            is MutableMap<*, *> -> @Suppress("UNCHECKED_CAST") (c as MutableMap<String, Any?>)
            else -> return fail(file, "${file.fileName} 里的 \"$containerKey\" 不是对象，没有写入", url)
        }
        // 保留用户在这一项里加的其他字段（例如 disabled），只覆盖我们管理的字段
        val merged = linkedMapOf<String, Any?>()
        (container[McpInstaller.SERVER_NAME] as? Map<*, *>)?.forEach { (k, v) -> merged[k.toString()] = v }
        merged.putAll(entry(url))
        container[McpInstaller.SERVER_NAME] = merged
        val text = McpInstaller.formatLike(original, Json.pretty(obj, if (original != null) Json.detectIndent(original) else "  "))
        if (text == original) return McpInstallOutcome(client, file, true, "已是最新", unchanged = true)
        return try {
            McpInstaller.writeAtomically(file, text)
            McpInstallOutcome(client, file, true, if (existed) "已更新" else "已创建", created = !existed)
        } catch (e: IOException) {
            fail(file, "写入 $file 失败：${e.message}", url)
        }
    }

    override fun uninstall(root: Path, home: Path): McpInstallOutcome {
        val file = configFile(root, home)
        if (!Files.isRegularFile(file)) return McpInstallOutcome(client, file, true, "未安装", unchanged = true)
        val original = readTextOrNull(file) ?: return McpInstallOutcome(client, file, false, "读不了 $file")
        val obj = try {
            Json.parseObject(original)
        } catch (e: Exception) {
            return McpInstallOutcome(client, file, false, "${file.fileName} 解析失败，请手动删掉 \"${McpInstaller.SERVER_NAME}\" 一项：${e.message}")
        }
        val container = obj[containerKey] as? MutableMap<*, *>
        if (container == null || container.remove(McpInstaller.SERVER_NAME) == null) {
            return McpInstallOutcome(client, file, true, "未安装", unchanged = true)
        }
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

    private fun fail(file: Path, message: String, url: String) =
        McpInstallOutcome(client, file, false, message, manualSnippet = snippet(url))
}

/**
 * Codex：用户级 `~/.codex/config.toml`。只做最小的 TOML 段替换，不引 TOML 库：
 * 找到 `[mcp_servers.emmylua-debug]` 段就替换段内容，没有就追加到文件末尾。
 */
class CodexConfigWriter : McpClientConfigWriter {
    override val client = McpClientKind.CODEX

    override fun configFile(root: Path, home: Path): Path = home.resolve(".codex").resolve("config.toml")

    override fun detect(root: Path, home: Path): Boolean = Files.isDirectory(home.resolve(".codex"))

    override fun snippet(url: String): String = "$HEADER\nurl = \"${escape(url)}\"\n"

    override fun status(root: Path, home: Path, url: String): McpClientConfigStatus {
        val file = configFile(root, home)
        val detected = detect(root, home)
        if (!Files.isRegularFile(file)) return McpClientConfigStatus(client, file, detected, installed = false)
        val text = readTextOrNull(file)
            ?: return McpClientConfigStatus(client, file, detected, false, error = "读不了 $file")
        val section = findSection(text.lines())
            ?: return McpClientConfigStatus(client, file, detected, installed = false)
        val current = text.lines().subList(section.first + 1, section.last + 1)
            .firstNotNullOfOrNull { URL_LINE.matchEntire(it.trim())?.groupValues?.get(1) }
        return McpClientConfigStatus(client, file, detected, true, outdated = !McpInstaller.sameUrl(current, url), currentUrl = current)
    }

    override fun install(root: Path, home: Path, url: String): McpInstallOutcome {
        val file = configFile(root, home)
        val existed = Files.isRegularFile(file)
        val original = if (existed) readTextOrNull(file)
            ?: return McpInstallOutcome(client, file, false, "读不了 $file", snippet(url)) else ""
        val eol = if (original.contains("\r\n")) "\r\n" else "\n"
        val lines = original.lines().toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.size - 1)
        val body = listOf(HEADER, "url = \"${escape(url)}\"")
        val section = findSection(lines)
        if (section != null) {
            // 保留段里我们不管的键（例如 enabled、startup_timeout_sec）
            val kept = lines.subList(section.first + 1, section.last + 1)
                .filter { it.isNotBlank() && URL_LINE.matchEntire(it.trim()) == null && !it.trim().startsWith("command") && !it.trim().startsWith("args") }
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
            McpInstallOutcome(client, file, false, "写入 $file 失败：${e.message}", snippet(url))
        }
    }

    override fun uninstall(root: Path, home: Path): McpInstallOutcome {
        val file = configFile(root, home)
        if (!Files.isRegularFile(file)) return McpInstallOutcome(client, file, true, "未安装", unchanged = true)
        val original = readTextOrNull(file) ?: return McpInstallOutcome(client, file, false, "读不了 $file")
        val eol = if (original.contains("\r\n")) "\r\n" else "\n"
        val lines = original.lines().toMutableList()
        val section = findSection(lines) ?: return McpInstallOutcome(client, file, true, "未安装", unchanged = true)
        repeat(section.last - section.first + 1) { lines.removeAt(section.first) }
        return try {
            McpInstaller.writeAtomically(file, lines.joinToString(eol).trimEnd() + eol)
            McpInstallOutcome(client, file, true, "已移除")
        } catch (e: IOException) {
            McpInstallOutcome(client, file, false, "写入 $file 失败：${e.message}")
        }
    }

    /** 段的行范围（含表头，到下一个表头前的最后一个非空行）。 */
    private fun findSection(lines: List<String>): IntRange? {
        val start = lines.indexOfFirst { HEADER_LINE.matches(it.trim()) }
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
        private const val HEADER = "[mcp_servers.${McpInstaller.SERVER_NAME}]"
        private val HEADER_LINE = Regex("""\[\s*mcp_servers\.(?:"${McpInstaller.SERVER_NAME}"|${McpInstaller.SERVER_NAME})\s*]\s*(#.*)?""")
        private val URL_LINE = Regex("""url\s*=\s*"([^"]*)"\s*(#.*)?""")
    }
}

/** 根据客户端选择写入器，统一处理多个客户端。 */
class McpInstaller(
    private val home: Path = Path.of(System.getProperty("user.home")),
    val writers: List<McpClientConfigWriter> = defaultWriters(),
) {
    fun writer(client: McpClientKind): McpClientConfigWriter = writers.first { it.client == client }

    fun status(root: Path, url: String): List<McpClientConfigStatus> =
        writers.map { w ->
            runCatching { w.status(root, home, url) }.getOrElse { e ->
                McpClientConfigStatus(w.client, w.configFile(root, home), w.detect(root, home), false, error = e.message)
            }
        }

    fun install(root: Path, url: String, clients: Collection<McpClientKind>): List<McpInstallOutcome> =
        clients.map { c -> writer(c).install(root, home, url) }

    fun uninstall(root: Path, clients: Collection<McpClientKind>): List<McpInstallOutcome> =
        clients.map { c -> writer(c).uninstall(root, home) }

    /** 「已安装」：任一项目级客户端的配置里有 emmylua-debug 且地址指向当前端口（设计文档 6.5）。 */
    fun isInstalled(root: Path, url: String): Boolean =
        status(root, url).any { it.client.projectLevel && it.installed && !it.outdated }

    /** 默认勾选：已检测到的客户端；一个都没有时默认 Claude Code。 */
    fun defaultSelection(root: Path): List<McpClientKind> {
        val detected = writers.filter { it.client.projectLevel && it.detect(root, home) }.map { it.client }
        return detected.ifEmpty { listOf(McpClientKind.CLAUDE_CODE) }
    }

    companion object {
        const val SERVER_NAME = "emmylua-debug"

        fun url(port: Int) = "http://127.0.0.1:$port/mcp"

        fun defaultWriters(): List<McpClientConfigWriter> = listOf(
            JsonMcpConfigWriter(
                McpClientKind.CLAUDE_CODE, ".mcp.json", "mcpServers",
                detectPaths = listOf(".mcp.json", ".claude", "CLAUDE.md"),
                urlKey = "url",
            ) { url -> linkedMapOf("type" to "http", "url" to url) },
            JsonMcpConfigWriter(
                McpClientKind.CURSOR, ".cursor/mcp.json", "mcpServers",
                detectPaths = listOf(".cursor", ".cursorrules"),
                urlKey = "url",
            ) { url -> linkedMapOf("url" to url) },
            JsonMcpConfigWriter(
                McpClientKind.VSCODE, ".vscode/mcp.json", "servers",
                detectPaths = listOf(".vscode"),
                urlKey = "url",
            ) { url -> linkedMapOf("type" to "http", "url" to url) },
            JsonMcpConfigWriter(
                McpClientKind.GEMINI, ".gemini/settings.json", "mcpServers",
                detectPaths = listOf(".gemini", "GEMINI.md"),
                urlKey = "httpUrl",
            ) { url -> linkedMapOf("httpUrl" to url) },
            CodexConfigWriter(),
        )

        /** localhost 和 127.0.0.1、末尾斜杠视为同一地址。 */
        fun sameUrl(a: String?, b: String?): Boolean {
            fun norm(s: String?) = s?.trim()?.trimEnd('/')?.replace("://localhost:", "://127.0.0.1:")
            return a != null && norm(a) == norm(b)
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

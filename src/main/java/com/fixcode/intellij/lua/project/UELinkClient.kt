package com.fixcode.intellij.lua.project

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 调 UE 进程里 UELink（UEKit）的 `/rpc`：按名片 `<UE项目>/Saved/UELink/instances/<PID>.json` 找端口和令牌。
 * 协议见 UEKit 仓库 docs/protocol.md 第 1、2 节。
 *
 * 名片的 `kind` 有 editor / game / server / background：编辑器（PIE 也在编辑器进程里）、`-game` 独立进程、
 * 专用服务器、后台编辑器（commandlet 之类）。HTTP 服务每种进程都可能有（`-NoAgentPanel` 启动时没有 `http` 字段）。
 * 声明生成（luadecl.*）只认编辑器，热重载（lua.reload）发给所有跑着 Lua 的实例。
 */
object UELinkClient {
    data class Instance(
        val pid: Long,
        /** HTTP 端口；进程没起 HTTP 服务时为空（名片照样能用来标注进程）。 */
        val port: Int?,
        val token: String,
        val startedAt: String,
        val kind: String = KIND_EDITOR,
        /** `.uproject` 的路径（名片原样）。 */
        val project: String? = null,
        val engine: String? = null,
        /** UELink 版本。 */
        val version: String? = null,
    ) {
        /** 给人看的进程类型。 */
        val label: String get() = kindLabel(kind)

        /** `.uproject` 去掉扩展名，例如 `Game`。 */
        val projectName: String? get() = project?.replace('\\', '/')?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }

        val hasHttp: Boolean get() = port != null && token.isNotEmpty()
    }

    const val KIND_EDITOR = "editor"
    const val KIND_GAME = "game"
    const val KIND_SERVER = "server"
    const val KIND_BACKGROUND = "background"

    fun kindLabel(kind: String): String = when (kind) {
        KIND_EDITOR -> "UE 编辑器"
        KIND_GAME -> "UE 游戏"
        KIND_SERVER -> "UE 专用服务器"
        KIND_BACKGROUND -> "UE 后台编辑器"
        else -> "UE（$kind）"
    }

    fun instancesDir(ueRoot: Path): Path = ueRoot.resolve("Saved").resolve("UELink").resolve("instances")

    /**
     * 这个 UE 项目里活着的所有实例，最新启动的在前；名片留下但进程没了的跳过（崩溃时名片不删）。
     * [requireHttp] 为 true 时只要能调 `/rpc` 的。
     */
    fun listInstances(ueRoot: Path, requireHttp: Boolean = true): List<Instance> {
        val dir = instancesDir(ueRoot)
        if (!Files.isDirectory(dir)) return emptyList()
        val cards = runCatching { Files.list(dir).use { s -> s.filter { it.toString().endsWith(".json") }.toList() } }
            .getOrDefault(emptyList())
        return cards.mapNotNull { parseCard(it) }
            .filter { !requireHttp || it.hasHttp }
            .filter { isAlive(it.pid) }
            .sortedByDescending { it.startedAt }
    }

    /** 同一项目开着几个编辑器时取最新启动的那个（和推送通道客户端的约定一致）。 */
    fun findEditor(ueRoot: Path): Instance? = listInstances(ueRoot).firstOrNull { it.kind == KIND_EDITOR }

    /** 按 PID 直接读名片（附加列表标注进程用），不检查进程是否活着：调用方手里的 PID 就是活进程。 */
    fun findByPid(ueRoot: Path, pid: Long): Instance? =
        instancesDir(ueRoot).resolve("$pid.json").takeIf { Files.isRegularFile(it) }?.let { parseCard(it) }?.takeIf { it.pid == pid }

    internal fun parseCard(file: Path): Instance? = runCatching { parseCardText(Files.readString(file)) }.getOrNull()

    /** 解析名片；缺 pid 的返回 null。没有 `kind` 的旧名片当编辑器（2.0 以前只有编辑器写名片）。 */
    fun parseCardText(text: String): Instance? = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        val pid = o.get("pid")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
        val port = (o.get("http") ?: o.get("port"))?.takeIf { it.isJsonPrimitive }?.asInt
        Instance(
            pid = pid,
            port = port,
            token = o.get("token")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            startedAt = o.get("startedAt")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            kind = o.get("kind")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: KIND_EDITOR,
            project = o.get("project")?.takeIf { it.isJsonPrimitive }?.asString,
            engine = o.get("engine")?.takeIf { it.isJsonPrimitive }?.asString,
            version = (o.get("version") ?: o.get("bridge"))?.takeIf { it.isJsonPrimitive }?.asString,
        )
    }.getOrNull()

    private fun isAlive(pid: Long): Boolean =
        runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(false)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(3)).build()
    }

    /** UE 那边没有这个操作（UELink 太旧，或 lua.* 没装 UnLua）。 */
    class OpMissingException(message: String) : IOException(message)

    /**
     * 成功返回整个回包（字段和 `ok` 平级）；UELink 回 ok=false 或连不上时抛 IOException，消息是给人看的中文。
     * [missingHint] 是操作不存在时告诉用户怎么办的那半句，默认按 luadecl（UELink 2.5.0）写。
     */
    fun call(
        instance: Instance,
        op: String,
        args: JsonObject = JsonObject(),
        timeout: Duration = Duration.ofSeconds(10),
        missingHint: String = "在 Agent 面板里把 UELink 升到 2.5.0 以上再编译",
    ): JsonObject {
        val port = instance.port ?: throw IOException("${instance.label}（PID ${instance.pid}）没有开 HTTP 服务（带 -NoAgentPanel 启动？）")
        val body = JsonObject().apply {
            addProperty("op", op)
            add("args", args)
        }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/rpc"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("X-AgentPanel-Token", instance.token)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IOException("连不上${instance.label}（端口 $port）：${e.message}", e)
        }
        val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
            ?: throw IOException("${instance.label}回了看不懂的内容（HTTP ${response.statusCode()}）")
        if (json.get("ok")?.asBoolean != true) {
            val error = json.get("error")?.takeIf { it.isJsonPrimitive }?.asString ?: "未知错误"
            // 旧版 UELink 没有这个操作（「这个进程不支持 xxx」/ unknown op）
            if (error.contains("不支持") || error.contains("unknown", ignoreCase = true)) {
                throw OpMissingException("${instance.label}里的 UELink 没有 $op：$missingHint。（$error）")
            }
            throw IOException(error)
        }
        return json
    }
}

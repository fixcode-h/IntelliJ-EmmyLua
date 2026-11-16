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
 * 调 UE 编辑器里 UELink（UEKit）的 `/rpc`：按名片 `<UE项目>/Saved/UELink/instances/<PID>.json` 找端口和令牌。
 * 协议见 UEKit 仓库 docs/protocol.md；这里只用来开关后台生成 Lua 声明（luadecl.*）。
 */
object UELinkClient {
    data class Instance(val pid: Long, val port: Int, val token: String, val startedAt: String)

    /** 同一项目开着几个编辑器时取最新启动的那个；名片留下但进程没了的跳过。 */
    fun findEditor(ueRoot: Path): Instance? {
        val dir = ueRoot.resolve("Saved").resolve("UELink").resolve("instances")
        if (!Files.isDirectory(dir)) return null
        val cards = runCatching { Files.list(dir).use { s -> s.filter { it.toString().endsWith(".json") }.toList() } }
            .getOrDefault(emptyList())
        return cards.mapNotNull { parseCard(it) }
            .filter { isAlive(it.pid) }
            .maxByOrNull { it.startedAt }
    }

    internal fun parseCard(file: Path): Instance? = runCatching {
        val o = JsonParser.parseString(Files.readString(file)).asJsonObject
        if (o.get("kind")?.asString != "editor") return null
        val port = (o.get("http") ?: o.get("port"))?.asInt ?: return null
        val token = o.get("token")?.asString ?: return null
        Instance(o.get("pid")?.asLong ?: return null, port, token, o.get("startedAt")?.asString ?: "")
    }.getOrNull()

    private fun isAlive(pid: Long): Boolean =
        runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(false)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(3)).build()
    }

    /** 成功返回结果对象；UELink 回 ok=false 或连不上时抛 IOException，消息是给人看的中文。 */
    fun call(instance: Instance, op: String, args: JsonObject = JsonObject(), timeout: Duration = Duration.ofSeconds(10)): JsonObject {
        val body = JsonObject().apply {
            addProperty("op", op)
            add("args", args)
        }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${instance.port}/rpc"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("X-AgentPanel-Token", instance.token)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IOException("连不上 UE 编辑器（端口 ${instance.port}）：${e.message}", e)
        }
        val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
            ?: throw IOException("UE 编辑器回了看不懂的内容（HTTP ${response.statusCode()}）")
        if (json.get("ok")?.asBoolean != true) {
            val error = json.get("error")?.asString ?: "未知错误"
            // 旧版 UELink 没有这个操作
            if (error.contains("不支持") || error.contains("unknown", ignoreCase = true)) {
                throw IOException("UE 项目里的 UELink 版本太旧，没有 $op：在 Agent 面板里把 UELink 升到 2.5.0 以上再编译。（$error）")
            }
            throw IOException(error)
        }
        return json
    }
}

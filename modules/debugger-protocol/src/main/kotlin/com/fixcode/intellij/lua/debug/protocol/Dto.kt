package com.fixcode.intellij.lua.debug.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject

// 协议 v3 的消息体，字段名就是线上的名字（见 docs/design/protocol-v3.md）。
// 约定：所有 id 是 1 ≤ x < 2^53 的整数，0 表示「无 / 全部」；threadId 是不透明字符串；
// pauseId 在每个 VM 内单调递增；frameId / ref 在连接内唯一，只在所属暂停内有效。

// ---------- 握手（第 5 节） ----------

data class ClientInfo(val name: String, val version: String? = null) : JsonDto {
    override fun toJson() = jsonObject { put("name", name); put("version", version) }

    companion object {
        fun fromJson(json: JsonObject) = ClientInfo(json.optString("name") ?: "", json.optString("version"))
    }
}

data class HelloArgs(
    val client: ClientInfo,
    val caps: List<String> = Capabilities.CLIENT_WANTED,
    val token: String? = null,
    val protocol: Int = Protocol.VERSION,
    val revision: Int = Protocol.REVISION,
    val maxFrameBytes: Int? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("protocol", protocol); put("revision", revision); put("client", client); put("token", token)
        putStrings("caps", caps); put("maxFrameBytes", maxFrameBytes)
    }

    companion object {
        fun fromJson(json: JsonObject) = HelloArgs(
            json.optObject("client")?.let(ClientInfo::fromJson) ?: ClientInfo(""), json.strings("caps"),
            json.optString("token"), json.reqInt("protocol"), json.optInt("revision") ?: 0, json.optInt("maxFrameBytes")
        )
    }
}

data class RuntimeInfo(val name: String, val version: String, val build: String? = null) : JsonDto {
    override fun toJson() = jsonObject { put("name", name); put("version", version); put("build", build) }

    companion object {
        fun fromJson(json: JsonObject) = RuntimeInfo(
            json.optString("name") ?: "", json.optString("version") ?: "", json.optString("build")
        )
    }
}

data class LuaInfo(val version: String, val jit: Boolean = false, val abi: String? = null) : JsonDto {
    val displayName: String get() = if (jit) "LuaJIT" else "Lua $version"

    override fun toJson() = jsonObject { put("version", version); put("jit", jit); put("abi", abi) }

    companion object {
        fun fromJson(json: JsonObject) = LuaInfo(json.optString("version") ?: "", json.optBoolean("jit") ?: false, json.optString("abi"))
    }
}

data class ProcessInfo(
    val pid: Long,
    val name: String,
    val packageName: String? = null,
    val buildId: String? = null
) : JsonDto {
    override fun toJson() = jsonObject { put("pid", pid); put("name", name); put("package", packageName); put("buildId", buildId) }

    companion object {
        fun fromJson(json: JsonObject) = ProcessInfo(
            json.optLong("pid") ?: 0, json.optString("name") ?: "", json.optString("package"), json.optString("buildId")
        )
    }
}

/** 第 6.1 节。vmId 在 runtime 进程内唯一且不复用，重连后不变。 */
data class VmInfo(
    val vmId: Long,
    val name: String,
    val state: String = VmStates.RUNNING,
    val generation: Long = 1,
    val sourceEpoch: Long = 1,
    val luaVersion: String? = null,
    val engine: String? = null,
    val context: String? = null,
    val mainThreadId: String? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("vmId", vmId); put("generation", generation); put("name", name); put("state", state)
        put("luaVersion", luaVersion); put("sourceEpoch", sourceEpoch); put("engine", engine)
        put("context", context); put("mainThreadId", mainThreadId)
    }

    companion object {
        fun fromJson(json: JsonObject) = VmInfo(
            json.reqLong("vmId"), json.optString("name") ?: "", json.optString("state") ?: VmStates.RUNNING,
            json.optLong("generation") ?: 1, json.optLong("sourceEpoch") ?: 1, json.optString("luaVersion"),
            json.optString("engine"), json.optString("context"), json.optString("mainThreadId")
        )
    }
}

/** `hello` 的 response 体。`caps` 是双方交集。 */
data class Welcome(
    val sessionId: String,
    val runtime: RuntimeInfo,
    val caps: List<String> = emptyList(),
    val vms: List<VmInfo> = emptyList(),
    val lua: LuaInfo? = null,
    val platform: String? = null,
    val arch: String? = null,
    val process: ProcessInfo? = null,
    val protocol: Int = Protocol.VERSION,
    val revision: Int = Protocol.REVISION,
    val maxFrameBytes: Int? = null
) : JsonDto {
    fun has(cap: String): Boolean = cap in caps

    override fun toJson() = jsonObject {
        put("protocol", protocol); put("revision", revision); put("sessionId", sessionId); put("runtime", runtime)
        put("lua", lua); put("platform", platform); put("arch", arch); put("process", process)
        putStrings("caps", caps); put("maxFrameBytes", maxFrameBytes); putObjects("vms", vms)
    }

    companion object {
        fun fromJson(json: JsonObject) = Welcome(
            json.optString("sessionId") ?: "", json.optObject("runtime")?.let(RuntimeInfo::fromJson) ?: RuntimeInfo("", ""),
            json.strings("caps"), json.objects("vms", VmInfo::fromJson), json.optObject("lua")?.let(LuaInfo::fromJson),
            json.optString("platform"), json.optString("arch"), json.optObject("process")?.let(ProcessInfo::fromJson),
            json.reqInt("protocol"), json.optInt("revision") ?: 0, json.optInt("maxFrameBytes")
        )
    }
}

// ---------- 配置（第 12、13 节） ----------

data class HelperScript(val name: String, val source: String) : JsonDto {
    override fun toJson() = jsonObject { put("name", name); put("source", source) }

    companion object {
        fun fromJson(json: JsonObject) = HelperScript(json.reqString("name"), json.reqString("source"))
    }
}

/** 发脚本**内容**，不发本机路径（真机上不存在）。 */
data class LoadHelpersArgs(val scripts: List<HelperScript>, val main: String) : JsonDto {
    override fun toJson() = jsonObject { putObjects("scripts", scripts); put("main", main) }

    companion object {
        fun fromJson(json: JsonObject) = LoadHelpersArgs(json.objects("scripts", HelperScript::fromJson), json.reqString("main"))
    }
}

/** 只写出非空字段：setOptions 只改给出的项。 */
data class SetOptionsArgs(
    val stopOnEntry: Boolean? = null,
    val exceptions: String? = null,
    val captureOutput: Boolean? = null,
    val allowLuaEval: Boolean? = null,
    val valuePathInvokeIndex: Boolean? = null,
    val evalTimeoutMs: Int? = null,
    val caseSensitive: Boolean? = null,
    val pauseLatencyMs: Int? = null,
    /** variable-view.md 13.5，只在协商了 richVariables 时下发；内容原样转发（设置页决定字段）。 */
    val variableView: JsonObject? = null,
    /** 错误聚合（第 21 节，能力 errorReport）：none / uncaught / all，取值同 [ExceptionModes]。 */
    val collectErrors: String? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("stopOnEntry", stopOnEntry); put("exceptions", exceptions); put("captureOutput", captureOutput)
        put("allowLuaEval", allowLuaEval); put("valuePathInvokeIndex", valuePathInvokeIndex)
        put("evalTimeoutMs", evalTimeoutMs); put("caseSensitive", caseSensitive); put("pauseLatencyMs", pauseLatencyMs)
        put("variableView", variableView); put("collectErrors", collectErrors)
    }

    /** 用 [other] 里给出的项覆盖本对象（setOptions 只改给出的项，本地也按同样规则累积）。 */
    fun merge(other: SetOptionsArgs) = SetOptionsArgs(
        other.stopOnEntry ?: stopOnEntry, other.exceptions ?: exceptions, other.captureOutput ?: captureOutput,
        other.allowLuaEval ?: allowLuaEval, other.valuePathInvokeIndex ?: valuePathInvokeIndex,
        other.evalTimeoutMs ?: evalTimeoutMs, other.caseSensitive ?: caseSensitive, other.pauseLatencyMs ?: pauseLatencyMs,
        other.variableView ?: variableView, other.collectErrors ?: collectErrors
    )

    companion object {
        fun fromJson(json: JsonObject) = SetOptionsArgs(
            json.optBoolean("stopOnEntry"), json.optString("exceptions"), json.optBoolean("captureOutput"),
            json.optBoolean("allowLuaEval"), json.optBoolean("valuePathInvokeIndex"), json.optInt("evalTimeoutMs"),
            json.optBoolean("caseSensitive"), json.optInt("pauseLatencyMs"), json.optObject("variableView"),
            json.optString("collectErrors")
        )
    }
}

// ---------- 断点（第 7 节） ----------

data class SourceRef(
    /** IDE 本机路径，统一 `/` 分隔。 */
    val path: String,
    /** 已知的 chunkName；给了 runtime 就只按它精确匹配。 */
    val chunkName: String? = null,
    val sha256: String? = null
) : JsonDto {
    override fun toJson() = jsonObject { put("path", path); put("chunkName", chunkName); put("sha256", sha256) }

    companion object {
        fun fromJson(json: JsonObject) = SourceRef(json.reqString("path"), json.optString("chunkName"), json.optString("sha256"))
    }
}

data class BreakpointSpec(
    val id: String,
    val source: SourceRef,
    val line: Int,
    val condition: String? = null,
    val hitCondition: String? = null,
    val logMessage: String? = null,
    /** 命中时按 valuePath 采集的值（原 CLI 的 Probe）。 */
    val capture: List<String> = emptyList(),
    /** 命中不停，只发 breakpointHit。有 logMessage 时 runtime 也视为 true。 */
    val autoContinue: Boolean = false,
    /** 0 表示所有 VM。 */
    val vmId: Long = 0
) : JsonDto {
    override fun toJson() = jsonObject {
        put("id", id); put("source", source); put("line", line); put("condition", condition)
        put("hitCondition", hitCondition); put("logMessage", logMessage)
        if (capture.isNotEmpty()) putStrings("capture", capture)
        if (autoContinue) put("autoContinue", true)
        if (vmId != 0L) put("vmId", vmId)
    }

    companion object {
        fun fromJson(json: JsonObject) = BreakpointSpec(
            json.reqString("id"), SourceRef.fromJson(json.reqObject("source")), json.reqInt("line"),
            json.optString("condition"), json.optString("hitCondition"), json.optString("logMessage"),
            json.strings("capture"), json.optBoolean("autoContinue") ?: false, json.optLong("vmId") ?: 0
        )
    }
}

data class SetBreakpointsArgs(
    /** 每个连接内单调递增，新连接从 1 开始。 */
    val revision: Long,
    val upserts: List<BreakpointSpec> = emptyList(),
    val removes: List<String> = emptyList(),
    /** 先清空再建（握手后、重连后的首次下发）。 */
    val replaceAll: Boolean = false
) : JsonDto {
    override fun toJson() = jsonObject {
        put("revision", revision); put("replaceAll", replaceAll); putObjects("upserts", upserts)
        if (!replaceAll) putStrings("removes", removes)
    }

    companion object {
        fun fromJson(json: JsonObject) = SetBreakpointsArgs(
            json.reqLong("revision"), json.objects("upserts", BreakpointSpec::fromJson), json.strings("removes"),
            json.optBoolean("replaceAll") ?: false
        )
    }
}

/** setBreakpoints response 里每个断点的初始状态：pending 或 invalid。 */
data class BreakpointAck(val id: String, val state: String, val message: String? = null) : JsonDto {
    override fun toJson() = jsonObject { put("id", id); put("state", state); put("message", message) }

    companion object {
        fun fromJson(json: JsonObject) = BreakpointAck(
            json.reqString("id"), json.optString("state") ?: BreakpointStateNames.PENDING, json.optString("message")
        )
    }
}

data class SetBreakpointsResult(val revision: Long, val breakpoints: List<BreakpointAck>) : JsonDto {
    override fun toJson() = jsonObject { put("revision", revision); putObjects("breakpoints", breakpoints) }

    companion object {
        fun fromJson(json: JsonObject) = SetBreakpointsResult(
            json.optLong("revision") ?: 0, json.objects("breakpoints", BreakpointAck::fromJson)
        )
    }
}

/** 事件 breakpointStatus：某个断点在某个 VM 里的验证结果。 */
data class BreakpointStatusEvent(
    val id: String,
    val vmId: Long,
    val state: String,
    val chunkName: String? = null,
    val line: Int? = null,
    val message: String? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("id", id); put("vmId", vmId); put("state", state); put("chunkName", chunkName); put("line", line)
        put("message", message)
    }

    companion object {
        fun fromJson(json: JsonObject) = BreakpointStatusEvent(
            json.reqString("id"), json.optLong("vmId") ?: 0, json.reqString("state"), json.optString("chunkName"),
            json.optInt("line"), json.optString("message")
        )
    }
}

data class CaptureValue(
    val expression: String,
    val value: String,
    val type: String,
    val error: String? = null
) : JsonDto {
    override fun toJson() = jsonObject { put("expression", expression); put("value", value); put("type", type); put("error", error) }

    companion object {
        fun fromJson(json: JsonObject) = CaptureValue(
            json.optString("expression") ?: "", json.optString("value") ?: "", json.optString("type") ?: "nil",
            json.optString("error")
        )
    }
}

/** 事件 breakpointHit：autoContinue / 日志断点每次命中（不暂停）。 */
data class BreakpointHitEvent(
    val id: String,
    val vmId: Long,
    val hitCount: Long,
    val line: Int,
    val threadId: String? = null,
    val timeMs: Long? = null,
    val captures: List<CaptureValue> = emptyList(),
    val dropped: Long? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("id", id); put("vmId", vmId); put("threadId", threadId); put("hitCount", hitCount); put("line", line)
        put("timeMs", timeMs); putObjects("captures", captures); put("dropped", dropped)
    }

    companion object {
        fun fromJson(json: JsonObject) = BreakpointHitEvent(
            json.reqString("id"), json.optLong("vmId") ?: 0, json.optLong("hitCount") ?: 0, json.optInt("line") ?: 0,
            json.optString("threadId"), json.optLong("timeMs"), json.objects("captures", CaptureValue::fromJson),
            json.optLong("dropped")
        )
    }
}

// ---------- VM 事件（第 6 节） ----------

data class VmStateChangedEvent(
    val vmId: Long,
    val state: String,
    val sourceEpoch: Long? = null,
    val reason: String? = null
) : JsonDto {
    override fun toJson() = jsonObject { put("vmId", vmId); put("state", state); put("sourceEpoch", sourceEpoch); put("reason", reason) }

    companion object {
        fun fromJson(json: JsonObject) = VmStateChangedEvent(
            json.reqLong("vmId"), json.optString("state") ?: VmStates.RUNNING, json.optLong("sourceEpoch"), json.optString("reason")
        )
    }
}

data class VmClosedEvent(val vmId: Long, val reason: String? = null) : JsonDto {
    override fun toJson() = jsonObject { put("vmId", vmId); put("reason", reason) }

    companion object {
        fun fromJson(json: JsonObject) = VmClosedEvent(json.reqLong("vmId"), json.optString("reason"))
    }
}

data class VmsResult(val vms: List<VmInfo>) : JsonDto {
    override fun toJson() = jsonObject { putObjects("vms", vms) }

    companion object {
        fun fromJson(json: JsonObject) = VmsResult(json.objects("vms", VmInfo::fromJson))
    }
}

data class ThreadInfo(val threadId: String, val status: String, val name: String? = null) : JsonDto {
    override fun toJson() = jsonObject { put("threadId", threadId); put("name", name); put("status", status) }

    companion object {
        fun fromJson(json: JsonObject) = ThreadInfo(json.reqString("threadId"), json.optString("status") ?: "suspended", json.optString("name"))
    }
}

data class ThreadsResult(val threads: List<ThreadInfo>) : JsonDto {
    override fun toJson() = jsonObject { putObjects("threads", threads) }

    companion object {
        fun fromJson(json: JsonObject) = ThreadsResult(json.objects("threads", ThreadInfo::fromJson))
    }
}

// ---------- 执行控制（第 8 节） ----------

/**
 * 控制命令的目标。对暂停中的 VM 发命令必须带当前 pauseId：
 * 旧版 IDE 先清空暂停快照再发不带 pauseId 的继续，runtime 拒绝后无人处理，目标就一直停在断点上。
 */
data class PauseTarget(val vmId: Long, val pauseId: Long? = null, val threadId: String? = null) : JsonDto {
    override fun toJson() = jsonObject { put("vmId", vmId); put("pauseId", pauseId); put("threadId", threadId) }

    companion object {
        fun fromJson(json: JsonObject) = PauseTarget(json.optLong("vmId") ?: 0, json.optLong("pauseId"), json.optString("threadId"))
    }
}

data class RunToLineArgs(val target: PauseTarget, val source: SourceRef, val line: Int) : JsonDto {
    override fun toJson() = target.toJson().apply { add("source", source.toJson()); addProperty("line", line) }

    companion object {
        fun fromJson(json: JsonObject) = RunToLineArgs(PauseTarget.fromJson(json), SourceRef.fromJson(json.reqObject("source")), json.reqInt("line"))
    }
}

data class DisconnectArgs(val terminate: Boolean = false) : JsonDto {
    override fun toJson() = jsonObject { put("terminate", terminate) }

    companion object {
        fun fromJson(json: JsonObject) = DisconnectArgs(json.optBoolean("terminate") ?: false)
    }
}

data class CancelArgs(val requestId: Long) : JsonDto {
    override fun toJson() = jsonObject { put("requestId", requestId) }

    companion object {
        fun fromJson(json: JsonObject) = CancelArgs(json.reqLong("requestId"))
    }
}

data class ExceptionInfo(val message: String, val traceback: String? = null, val uncaught: Boolean = true) : JsonDto {
    override fun toJson() = jsonObject { put("message", message); put("traceback", traceback); put("uncaught", uncaught) }

    companion object {
        fun fromJson(json: JsonObject) = ExceptionInfo(json.optString("message") ?: "", json.optString("traceback"), json.optBoolean("uncaught") ?: true)
    }
}

/** paused 事件只带身份和栈顶帧；栈和变量按需拉取。 */
data class PausedEvent(
    val vmId: Long,
    val pauseId: Long,
    val reason: String,
    val threadId: String? = null,
    val top: StackFrameDto? = null,
    val breakpointIds: List<String> = emptyList(),
    val description: String? = null,
    val exception: ExceptionInfo? = null
) : JsonDto {
    fun target() = PauseTarget(vmId, pauseId, threadId)

    override fun toJson() = jsonObject {
        put("vmId", vmId); put("pauseId", pauseId); put("threadId", threadId); put("reason", reason)
        if (breakpointIds.isNotEmpty()) putStrings("breakpointIds", breakpointIds)
        put("description", description); put("top", top); put("exception", exception)
    }

    companion object {
        fun fromJson(json: JsonObject) = PausedEvent(
            json.reqLong("vmId"), json.reqLong("pauseId"), json.optString("reason") ?: PauseReasons.PAUSE,
            json.optString("threadId"), json.optObject("top")?.let(StackFrameDto::fromJson), json.strings("breakpointIds"),
            json.optString("description"), json.optObject("exception")?.let(ExceptionInfo::fromJson)
        )
    }
}

data class ResumedEvent(val vmId: Long, val pauseId: Long? = null, val by: String? = null) : JsonDto {
    override fun toJson() = jsonObject { put("vmId", vmId); put("pauseId", pauseId); put("by", by) }

    companion object {
        fun fromJson(json: JsonObject) = ResumedEvent(json.reqLong("vmId"), json.optLong("pauseId"), json.optString("by"))
    }
}

// ---------- 取数据（第 9 节） ----------

data class StackTraceArgs(val target: PauseTarget, val start: Int = 0, val count: Int = 20) : JsonDto {
    override fun toJson() = target.toJson().apply { addProperty("start", start); addProperty("count", count) }

    companion object {
        fun fromJson(json: JsonObject) = StackTraceArgs(PauseTarget.fromJson(json), json.optInt("start") ?: 0, json.optInt("count") ?: 20)
    }
}

data class StackFrameDto(
    val frameId: Long,
    val name: String,
    val chunkName: String,
    val line: Int,
    val level: Int = 0,
    /** Lua / C / main / tail。C 帧 chunkName 为 `=[C]`、line 为 -1。 */
    val what: String? = null,
    val threadId: String? = null,
    val lineDefined: Int? = null,
    val lastLineDefined: Int? = null
) : JsonDto {
    val hasSource: Boolean get() = line > 0 && what != "C" && !chunkName.startsWith("=")

    override fun toJson() = jsonObject {
        put("frameId", frameId); put("level", level); put("threadId", threadId); put("name", name); put("what", what)
        put("chunkName", chunkName); put("line", line); put("lineDefined", lineDefined); put("lastLineDefined", lastLineDefined)
    }

    companion object {
        fun fromJson(json: JsonObject) = StackFrameDto(
            json.reqLong("frameId"), json.optString("name") ?: "?", json.optString("chunkName") ?: "", json.optInt("line") ?: -1,
            json.optInt("level") ?: 0, json.optString("what"), json.optString("threadId"), json.optInt("lineDefined"),
            json.optInt("lastLineDefined")
        )
    }
}

// ---------- 协程（6.4 threads） ----------

data class ThreadsArgs(val vmId: Long, val pauseId: Long? = null) : JsonDto {
    override fun toJson() = jsonObject { put("vmId", vmId); put("pauseId", pauseId) }

    companion object {
        fun fromJson(json: JsonObject) = ThreadsArgs(json.optLong("vmId") ?: 0, json.optLong("pauseId"))
    }
}

data class StackTraceResult(val frames: List<StackFrameDto>, val total: Int) : JsonDto {
    override fun toJson() = jsonObject { putObjects("frames", frames); put("total", total) }

    companion object {
        fun fromJson(json: JsonObject): StackTraceResult {
            val frames = json.objects("frames", StackFrameDto::fromJson)
            return StackTraceResult(frames, json.optInt("total") ?: frames.size)
        }
    }
}

data class ScopesArgs(val frameId: Long) : JsonDto {
    override fun toJson() = jsonObject { put("frameId", frameId) }

    companion object {
        fun fromJson(json: JsonObject) = ScopesArgs(json.reqLong("frameId"))
    }
}

data class ScopeDto(val name: String, val ref: Long, val count: Int? = null) : JsonDto {
    override fun toJson() = jsonObject { put("name", name); put("ref", ref); put("count", count) }

    companion object {
        fun fromJson(json: JsonObject) = ScopeDto(json.reqString("name"), json.reqLong("ref"), json.optInt("count"))
    }
}

data class ScopesResult(val scopes: List<ScopeDto>) : JsonDto {
    override fun toJson() = jsonObject { putObjects("scopes", scopes) }

    companion object {
        fun fromJson(json: JsonObject) = ScopesResult(json.objects("scopes", ScopeDto::fromJson))
    }
}

data class VariablesArgs(
    val ref: Long,
    val start: Int = 0,
    val count: Int = 100,
    val filter: String? = null,
    /** 以下三项是 richVariables 增量（variable-view.md 13.3），没协商时不发。 */
    val view: String? = null,
    val maxValueLength: Int? = null,
    /** 顺带展开的子路径（按显示名），结果放在对应变量的 children 里。 */
    val expand: List<List<String>>? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("ref", ref); put("start", start); put("count", count); put("filter", filter)
        put("view", view); put("maxValueLength", maxValueLength)
        if (!expand.isNullOrEmpty()) put("expand", JsonArray().apply { expand.forEach { path -> add(JsonArray().apply { path.forEach(::add) }) } })
    }

    companion object {
        fun fromJson(json: JsonObject) = VariablesArgs(
            json.reqLong("ref"), json.optInt("start") ?: 0, json.optInt("count") ?: 100, json.optString("filter"),
            json.optString("view"), json.optInt("maxValueLength"),
            json.optArray("expand")?.map { path -> path.asJsonArray.map { it.asString } }
        )
    }
}

/** 函数定义处、协程栈顶、模块文件（variable-view.md 13.2）。 */
data class LocationDto(val chunkName: String, val line: Int) : JsonDto {
    override fun toJson() = jsonObject { put("chunkName", chunkName); put("line", line) }

    companion object {
        fun fromJson(json: JsonObject) = LocationDto(json.optString("chunkName") ?: "", json.optInt("line") ?: 0)
    }
}

data class VariableDto(
    val name: String,
    val value: String,
    val type: String,
    /** 大于 0 表示可展开，用它调 variables；叶子为 0。 */
    val ref: Long = 0,
    val typeName: String? = null,
    val nameType: String? = null,
    val namedCount: Int? = null,
    val indexedCount: Int? = null,
    /** 能在 valuePath 语法下重新取到这个值的表达式（添加到监视、setVariable 用）。 */
    val evaluateName: String? = null,
    // ---- 以下是 richVariables 增量（variable-view.md 13.2），没协商时全为空 ----
    /** 显示形态：array / record / object / class / module / function / group …，未知值按 type 显示。 */
    val kind: String? = null,
    /** inherited / shadowed / metamethod / invalid / readOnly / lazy / pinned / truncated / helperError */
    val attrs: List<String>? = null,
    /** 地址 hex，本次暂停内有效，用来识别循环引用。 */
    val objectId: String? = null,
    /** 字符串完整字节长度，或容器元素总数。 */
    val length: Long? = null,
    val location: LocationDto? = null,
    /** 行尾灰色提示。 */
    val hint: String? = null,
    /** 只在请求了 expand 时出现。 */
    val children: VariablesResult? = null
) : JsonDto {
    val expandable: Boolean get() = ref > 0

    fun hasAttr(attr: String): Boolean = attrs?.contains(attr) == true

    override fun toJson() = jsonObject {
        put("name", name); put("nameType", nameType); put("value", value); put("type", type); put("typeName", typeName)
        put("ref", ref); put("namedCount", namedCount); put("indexedCount", indexedCount); put("evaluateName", evaluateName)
        put("kind", kind); putStrings("attrs", attrs); put("objectId", objectId); put("length", length)
        put("location", location); put("hint", hint); put("children", children)
    }

    companion object {
        fun fromJson(json: JsonObject): VariableDto = VariableDto(
            json.optString("name") ?: "", json.optString("value") ?: "", json.optString("type") ?: "nil",
            json.optLong("ref") ?: 0, json.optString("typeName"), json.optString("nameType"), json.optInt("namedCount"),
            json.optInt("indexedCount"), json.optString("evaluateName"),
            json.optString("kind"), json.optArray("attrs")?.map { it.asString }, json.optString("objectId"), json.optLong("length"),
            json.optObject("location")?.let(LocationDto::fromJson), json.optString("hint"),
            json.optObject("children")?.let(VariablesResult::fromJson)
        )
    }
}

data class VariablesResult(
    val variables: List<VariableDto>,
    val total: Int,
    /** 底部分组（继承成员、方法、元表……），不计入 total；richVariables 增量。 */
    val groups: List<VariableDto> = emptyList(),
    val sorted: Boolean? = null,
    /** 因字节上限少给了项。 */
    val truncated: Boolean? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        putObjects("variables", variables); put("total", total)
        if (groups.isNotEmpty()) putObjects("groups", groups)
        put("sorted", sorted); put("truncated", truncated)
    }

    companion object {
        fun fromJson(json: JsonObject): VariablesResult {
            val variables = json.objects("variables", VariableDto::fromJson)
            return VariablesResult(
                variables, json.optInt("total") ?: variables.size,
                json.objects("groups", VariableDto::fromJson), json.optBoolean("sorted"), json.optBoolean("truncated")
            )
        }
    }
}

/** setViewRules（variable-view.md 13.4）：整体替换显示规则。 */
data class SetViewRulesArgs(val rules: JsonObject) : JsonDto {
    override fun toJson() = jsonObject { put("rules", rules) }

    companion object {
        fun fromJson(json: JsonObject) = SetViewRulesArgs(json.reqObject("rules"))
    }
}

data class ViewRuleWarning(val type: String?, val message: String) : JsonDto {
    override fun toJson() = jsonObject { put("type", type); put("message", message) }

    companion object {
        fun fromJson(json: JsonObject) = ViewRuleWarning(json.optString("type"), json.optString("message") ?: "")
    }
}

data class SetViewRulesResult(val accepted: Int, val warnings: List<ViewRuleWarning> = emptyList()) : JsonDto {
    override fun toJson() = jsonObject { put("accepted", accepted); putObjects("warnings", warnings) }

    companion object {
        fun fromJson(json: JsonObject) = SetViewRulesResult(json.optInt("accepted") ?: 0, json.objects("warnings", ViewRuleWarning::fromJson))
    }
}

// ---------- 求值（第 10 节） ----------

data class EvaluateArgs(
    val frameId: Long,
    val expression: String,
    val mode: String = EvalModes.VALUE_PATH,
    val context: String? = null,
    val maxLength: Int? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("frameId", frameId); put("expression", expression); put("mode", mode); put("context", context); put("maxLength", maxLength)
    }

    companion object {
        fun fromJson(json: JsonObject) = EvaluateArgs(
            json.optLong("frameId") ?: 0, json.reqString("expression"), json.optString("mode") ?: EvalModes.VALUE_PATH,
            json.optString("context"), json.optInt("maxLength")
        )
    }
}

/** evaluate / setVariable 的结果。 */
data class ValueResult(val value: String, val type: String, val ref: Long = 0, val typeName: String? = null) : JsonDto {
    override fun toJson() = jsonObject { put("value", value); put("type", type); put("ref", ref); put("typeName", typeName) }

    companion object {
        fun fromJson(json: JsonObject) = ValueResult(
            json.optString("value") ?: "", json.optString("type") ?: "nil", json.optLong("ref") ?: 0, json.optString("typeName")
        )
    }
}

data class SetVariableArgs(val ref: Long, val name: String, val value: String, val mode: String = EvalModes.LUA) : JsonDto {
    override fun toJson() = jsonObject { put("ref", ref); put("name", name); put("value", value); put("mode", mode) }

    companion object {
        fun fromJson(json: JsonObject) = SetVariableArgs(
            json.reqLong("ref"), json.reqString("name"), json.reqString("value"), json.optString("mode") ?: EvalModes.LUA
        )
    }
}

// ---------- 采样性能分析（第 20 节，能力 profiler） ----------

/**
 * profile 的参数。vmId = 0 表示所有 VM（start / stop 作用于全部，result 合并全部）。
 * intervalInstructions、durationMs 只有 start 用；sort、limit、stackLimit 只有 stop / result 用。
 */
data class ProfileArgs(
    val action: String,
    val vmId: Long = 0,
    val intervalInstructions: Int? = null,
    val durationMs: Int? = null,
    val sort: String? = null,
    val limit: Int? = null,
    val stackLimit: Int? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("action", action); if (vmId != 0L) put("vmId", vmId); put("intervalInstructions", intervalInstructions)
        put("durationMs", durationMs); put("sort", sort); put("limit", limit); put("stackLimit", stackLimit)
    }

    companion object {
        fun fromJson(json: JsonObject) = ProfileArgs(
            json.reqString("action"), json.optLong("vmId") ?: 0, json.optInt("intervalInstructions"), json.optInt("durationMs"),
            json.optString("sort"), json.optInt("limit"), json.optInt("stackLimit")
        )
    }
}

data class ProfileStartResult(val vms: List<Long>, val intervalInstructions: Int, val durationMs: Int = 0) : JsonDto {
    override fun toJson() = jsonObject { putLongs("vms", vms); put("intervalInstructions", intervalInstructions); put("durationMs", durationMs) }

    companion object {
        fun fromJson(json: JsonObject) = ProfileStartResult(
            json.longs("vms"), json.optInt("intervalInstructions") ?: 0, json.optInt("durationMs") ?: 0
        )
    }
}

/** 函数表的一行。source 是 chunkName 原样（C 函数为 "=[C]"），line 是 linedefined（C 函数 -1、主 chunk 0）。 */
data class ProfileFunctionDto(
    val fn: String,
    val source: String,
    val line: Int,
    val selfSamples: Long,
    val totalSamples: Long,
    val selfPercent: Double,
    val totalPercent: Double
) : JsonDto {
    val isC: Boolean get() = source == "=[C]"

    override fun toJson() = jsonObject {
        put("fn", fn); put("source", source); put("line", line); put("selfSamples", selfSamples); put("totalSamples", totalSamples)
        put("selfPercent", selfPercent); put("totalPercent", totalPercent)
    }

    companion object {
        fun fromJson(json: JsonObject) = ProfileFunctionDto(
            json.optString("fn") ?: "?", json.optString("source") ?: "", json.optInt("line") ?: 0,
            json.optLong("selfSamples") ?: 0, json.optLong("totalSamples") ?: 0,
            json.optDouble("selfPercent") ?: 0.0, json.optDouble("totalPercent") ?: 0.0
        )
    }
}

/** 折叠栈：stack 是根在前、';' 分隔的帧（与 flamegraph.pl / speedscope 的 .folded 格式一致）。 */
data class ProfileStackDto(val stack: String, val samples: Long) : JsonDto {
    override fun toJson() = jsonObject { put("stack", stack); put("samples", samples) }

    companion object {
        fun fromJson(json: JsonObject) = ProfileStackDto(json.optString("stack") ?: "", json.optLong("samples") ?: 0)
    }
}

data class ProfileResult(
    val running: Boolean,
    val vms: List<Long>,
    val samples: Long,
    val durationMs: Long,
    val intervalInstructions: Int,
    val functions: List<ProfileFunctionDto>,
    val totalFunctions: Int,
    val stacks: List<ProfileStackDto>,
    val totalStacks: Int,
    val stacksTruncated: Boolean = false,
    val overheadMs: Double? = null,
    val pausedSkipped: Long? = null,
    val truncatedSamples: Long? = null,
    val otherSamples: Long? = null
) : JsonDto {
    /** 折叠栈文本（每行「帧;帧;帧 次数」），可直接存成 .folded。 */
    fun folded(): String = buildString { stacks.forEach { append(it.stack).append(' ').append(it.samples).append('\n') } }

    override fun toJson() = jsonObject {
        put("running", running); putLongs("vms", vms); put("samples", samples); put("durationMs", durationMs)
        put("intervalInstructions", intervalInstructions); putObjects("functions", functions); put("totalFunctions", totalFunctions)
        putObjects("stacks", stacks); put("totalStacks", totalStacks); put("stacksTruncated", stacksTruncated)
        put("overheadMs", overheadMs); put("pausedSkipped", pausedSkipped); put("truncatedSamples", truncatedSamples)
        put("otherSamples", otherSamples)
    }

    companion object {
        fun fromJson(json: JsonObject): ProfileResult {
            val functions = json.objects("functions", ProfileFunctionDto::fromJson)
            val stacks = json.objects("stacks", ProfileStackDto::fromJson)
            return ProfileResult(
                json.optBoolean("running") ?: false, json.longs("vms"), json.optLong("samples") ?: 0, json.optLong("durationMs") ?: 0,
                json.optInt("intervalInstructions") ?: 0, functions, json.optInt("totalFunctions") ?: functions.size,
                stacks, json.optInt("totalStacks") ?: stacks.size, json.optBoolean("stacksTruncated") ?: false,
                json.optDouble("overheadMs"), json.optLong("pausedSkipped"), json.optLong("truncatedSamples"), json.optLong("otherSamples")
            )
        }
    }
}

// ---------- 输出与其他 ----------

data class OutputEvent(
    val category: String,
    val text: String,
    val vmId: Long? = null,
    val chunkName: String? = null,
    val line: Int? = null,
    val level: String? = null,
    val dropped: Long? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("category", category); put("text", text); put("vmId", vmId); put("chunkName", chunkName); put("line", line)
        put("level", level); put("dropped", dropped)
    }

    companion object {
        fun fromJson(json: JsonObject) = OutputEvent(
            json.optString("category") ?: OutputCategories.STDOUT, json.optString("text") ?: "", json.optLong("vmId"),
            json.optString("chunkName"), json.optInt("line"), json.optString("level"), json.optLong("dropped")
        )
    }
}

data class TerminatedEvent(val reason: String? = null) : JsonDto {
    override fun toJson() = jsonObject { put("reason", reason) }

    companion object {
        fun fromJson(json: JsonObject) = TerminatedEvent(json.optString("reason"))
    }
}

// ---------- 错误聚合（第 21 节） ----------

/** 一条聚合后的 Lua 错误：luaError 事件和 errors 的条目同形。推送里的计数是限流过的，errors 的为准。 */
data class LuaErrorDto(
    val id: Long,
    val vmId: Long,
    val message: String,
    val count: Long,
    val firstMs: Long,
    val lastMs: Long,
    val chunkName: String? = null,
    val line: Int? = null,
    val uncaught: Boolean = false,
    /** 只在第一次推送和 errors 里有。 */
    val traceback: String? = null
) : JsonDto {
    override fun toJson() = jsonObject {
        put("id", id); put("vmId", vmId); put("message", message); put("count", count); put("firstMs", firstMs); put("lastMs", lastMs)
        put("chunkName", chunkName); put("line", line); if (uncaught) put("uncaught", true); put("traceback", traceback)
    }

    companion object {
        fun fromJson(json: JsonObject) = LuaErrorDto(
            json.optLong("id") ?: 0, json.optLong("vmId") ?: 0, json.optString("message") ?: "", json.optLong("count") ?: 1,
            json.optLong("firstMs") ?: 0, json.optLong("lastMs") ?: 0, json.optString("chunkName"), json.optInt("line"),
            json.optBoolean("uncaught") ?: false, json.optString("traceback")
        )
    }
}

data class ErrorsArgs(val action: String = ErrorsActions.LIST) : JsonDto {
    override fun toJson() = jsonObject { put("action", action) }
}

object ErrorsActions {
    const val LIST = "list"
    const val CLEAR = "clear"
}

data class ErrorsResult(val items: List<LuaErrorDto>, val dropped: Long = 0) : JsonDto {
    override fun toJson() = jsonObject { putObjects("items", items); if (dropped > 0) put("dropped", dropped) }

    companion object {
        fun fromJson(json: JsonObject) = ErrorsResult(json.objects("items", LuaErrorDto::fromJson), json.optLong("dropped") ?: 0)
    }
}

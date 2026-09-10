package com.tang.intellij.emmydebug

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.scalasbt.ipcsocket.Win32NamedPipeSocket
import com.tang.intellij.lua.debugger.cli.CliError
import com.tang.intellij.lua.debugger.cli.CliErrorCodes
import com.tang.intellij.lua.debugger.cli.CliInstanceDescriptor
import com.tang.intellij.lua.debugger.cli.CliInstanceDirectory
import com.tang.intellij.lua.debugger.cli.CliJsonLines
import com.tang.intellij.lua.debugger.cli.CliOperations
import com.tang.intellij.lua.debugger.cli.CliRequest
import com.tang.intellij.lua.debugger.cli.CliResponse
import com.tang.intellij.lua.debugger.cli.CliProtocolException
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.system.exitProcess

/** Parsed command line. Kept dependency-free so the CLI starts in a plain JVM. */
data class ParsedCliArgs(
    val positionals: List<String>,
    val options: Map<String, String?>,
    val captures: List<String> = emptyList()
) {
    fun option(name: String): String? = options[name]
    fun has(name: String): Boolean = options.containsKey(name)
}

object CliArgs {
    private val valueOptions = setOf(
        "instance", "endpoint", "token-file", "client", "request-id", "target", "vm", "pause", "frame",
        "thread", "path", "expression", "policy", "timeout-ms", "deadline-ms", "limit", "cursor", "lease",
        "lease-id", "probe-id", "breakpoint-id", "breakpoint-ids", "line", "condition", "hit-limit",
        "max-depth", "max-nodes", "max-bytes", "capture", "source-identity", "arguments", "variables-reference", "action",
        "cancel-request", "file", "hit-count", "timeout", "scope", "log-message", "hit-condition", "breakpoints"
    )
    private val flagOptions = setOf("help", "json", "auto-continue")

    fun parse(args: Array<String>): ParsedCliArgs {
        val positionals = mutableListOf<String>()
        val options = linkedMapOf<String, String?>()
        val captures = mutableListOf<String>()
        var index = 0
        var endOptions = false
        while (index < args.size) {
            val value = args[index]
            if (endOptions || !value.startsWith("--") || value == "--") {
                if (value == "--") endOptions = true
                else positionals += value
                index++
                continue
            }
            val raw = value.removePrefix("--")
            require(raw.isNotBlank()) { "option name must not be empty" }
            val equals = raw.indexOf('=')
            val name = if (equals >= 0) raw.substring(0, equals) else raw
            require(name in valueOptions || name in flagOptions) { "unknown option --$name" }
            if (name in flagOptions) {
                require(equals < 0) { "flag --$name does not accept a value" }
                require(!options.containsKey(name)) { "duplicate option --$name" }
                options[name] = null
                index++
                continue
            }
            val optionValue = if (equals >= 0) {
                raw.substring(equals + 1).takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--$name requires a value")
            } else {
                val next = args.getOrNull(index + 1)
                require(next != null && next != "--" && !next.startsWith("--")) {
                    "--$name requires a value"
                }
                next
            }
            if (name == "capture") {
                captures += splitCaptures(optionValue)
                options[name] = optionValue
            } else {
                require(!options.containsKey(name)) { "duplicate option --$name" }
                options[name] = optionValue
            }
            index += if (equals >= 0) 1 else 2
        }
        return ParsedCliArgs(positionals, options, captures)
    }

    // 兼容逗号分隔列表，同时保留 table["a,b"] 中的原始 key。
    private fun splitCaptures(value: String): List<String> {
        val result = mutableListOf<String>()
        var quote: Char? = null
        var escaped = false
        var depth = 0
        var start = 0
        value.forEachIndexed { index, ch ->
            if (quote != null) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == quote) quote = null
            } else when (ch) {
                '\'', '"' -> quote = ch
                '[' -> depth++
                ']' -> { depth--; require(depth >= 0) { "invalid --capture brackets" } }
                ',' -> if (depth == 0) {
                    result += value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        require(quote == null && depth == 0) { "unterminated --capture path" }
        result += value.substring(start).trim()
        require(result.none(String::isBlank)) { "--capture contains an empty path" }
        return result
    }
}

private const val DEFAULT_CLIENT = "emmy-debug"

/** Small JSONL client shared by the command dispatcher and tests. */
class GatewayClient(
    private val descriptor: CliInstanceDescriptor,
    private val token: String,
    private val connectTimeoutMillis: Int = 5_000
) : AutoCloseable {
    private val gson = Gson()
    private var socket: Socket? = null
    private var reader: InputStream? = null
    private var writer: BufferedWriter? = null

    fun connect() {
        check(socket == null) { "client is already connected" }
        val connected = when {
            descriptor.endpoint.startsWith("npipe://") -> {
                val name = descriptor.endpoint.removePrefix("npipe://")
                Win32NamedPipeSocket("\\\\.\\pipe\\$name")
            }
            descriptor.endpoint.startsWith("tcp://") -> {
                val address = parseTcpEndpoint(descriptor.endpoint.removePrefix("tcp://"))
                Socket().also { it.connect(address, connectTimeoutMillis) }
            }
            else -> throw IOException("unsupported endpoint: ${descriptor.endpoint}")
        }
        socket = connected
        reader = BufferedInputStream(connected.getInputStream())
        writer = connected.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
        sendRaw(gson.toJson(mapOf("token" to token)))
        val auth = readRaw() ?: throw IOException("gateway closed during authentication")
        val authJson = JsonParser.parseString(auth).asJsonObject
        if (authJson.get("ok")?.asBoolean != true) {
            val message = authJson.getAsJsonObject("error")?.get("message")?.asString ?: "authentication failed"
            throw GatewayClientException(CliErrorCodes.NOT_AUTHORIZED, message)
        }
    }

    fun request(request: CliRequest): CliResponse {
        ensureConnected()
        sendRaw(CliJsonLines.encode(request))
        return decodeResponse(readRaw() ?: throw IOException("gateway closed before response"))
    }

    /** Reads a wait stream until its terminal done record. */
    fun stream(request: CliRequest, emit: (CliResponse) -> Unit): List<CliResponse> {
        ensureConnected()
        sendRaw(CliJsonLines.encode(request))
        val responses = mutableListOf<CliResponse>()
        while (true) {
            val line = readRaw() ?: throw IOException("gateway closed while waiting")
            val response = decodeResponse(line)
            responses += response
            emit(response)
            if (response.done == true) return responses
        }
    }

    fun cancel(targetId: String?, requestId: String, clientId: String): CliResponse {
        val args = JsonObject().apply { addProperty("waitRequestId", requestId) }
        return request(CliRequest(
            requestId = "cancel-${UUID.randomUUID()}",
            operation = CliOperations.CANCEL,
            targetId = targetId,
            arguments = args,
            clientId = clientId
        ))
    }

    private fun parseTcpEndpoint(value: String): InetSocketAddress {
        val separator = value.lastIndexOf(':')
        require(separator > 0 && separator < value.length - 1) { "invalid TCP endpoint" }
        val host = value.substring(0, separator).removePrefix("[").removeSuffix("]")
        val port = value.substring(separator + 1).toIntOrNull()
            ?: throw IllegalArgumentException("invalid TCP port")
        require(port in 1..65535) { "invalid TCP port" }
        return InetSocketAddress(host, port)
    }

    private fun ensureConnected() {
        if (socket == null) connect()
    }

    private fun sendRaw(line: String) {
        val output = requireNotNull(writer)
        synchronized(output) {
            output.write(line)
            output.newLine()
            output.flush()
        }
    }

    private fun readRaw(): String? = try {
        CliJsonLines.readBoundedLine(requireNotNull(reader))
    } catch (error: CliProtocolException) {
        throw IOException(error.message, error)
    }

    private fun decodeResponse(line: String): CliResponse = runCatching {
        gson.fromJson(line, CliResponse::class.java)
    }.getOrElse { throw IOException("invalid gateway response", it) }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }
}

class GatewayClientException(val code: String, override val message: String) : RuntimeException(message)

private data class CommandContext(
    val args: ParsedCliArgs,
    val requestId: String,
    val clientId: String
)

fun main(rawArgs: Array<String>) {
    val exitCode = try {
        runCli(rawArgs)
    } catch (error: GatewayClientException) {
        printError(error.code, error.message)
        exitCodeFor(error.code)
    } catch (error: IllegalArgumentException) {
        printError("INVALID_ARGUMENT", error.message ?: "invalid argument")
        2
    } catch (error: IOException) {
        printError("GATEWAY_UNAVAILABLE", error.message ?: "gateway unavailable", retryable = true)
        3
    } catch (error: Throwable) {
        System.err.println("emmy-debug: ${error.message ?: error::class.java.simpleName}")
        70
    }
    exitProcess(exitCode)
}

fun runCli(rawArgs: Array<String>): Int {
    val parsed = CliArgs.parse(rawArgs)
    val command = parsed.positionals.firstOrNull()?.lowercase()
    if (parsed.has("help") || command == "help") {
        System.err.println(usage())
        return 0
    }
    requireNotNull(command) { usage() }
    if (command == "instance") {
        require(parsed.positionals.size == 1 || parsed.positionals[1].equals("list", true)) {
            "instance requires list"
        }
        return listInstances(parsed)
    }

    val descriptor = selectDescriptor(parsed)
    val token = readToken(Path.of(requireNotNull(parsed.option("token-file") ?: descriptor.tokenFile)))
    val context = CommandContext(
        parsed,
        parsed.option("request-id") ?: "cli-${UUID.randomUUID()}",
        parsed.option("client") ?: DEFAULT_CLIENT
    )
    GatewayClient(descriptor, token).use { client ->
        client.connect()
        return dispatch(client, command, context)
    }
}

private fun listInstances(args: ParsedCliArgs): Int {
    val selected = args.option("instance")
    val descriptors = CliInstanceDirectory.descriptorFiles()
        .mapNotNull(CliInstanceDirectory::read)
        .filter { descriptor -> ProcessHandle.of(descriptor.pid).map { it.isAlive }.orElse(false) }
        .filter { selected == null || it.ideaInstanceId == selected }
    val data = JsonObject().apply {
        val safe = descriptors.map { descriptor ->
            mapOf(
                "schemaVersion" to descriptor.schemaVersion,
                "ideaInstanceId" to descriptor.ideaInstanceId,
                "pid" to descriptor.pid,
                "product" to descriptor.product,
                "endpoint" to descriptor.endpoint,
                "startedAt" to descriptor.startedAt
            )
        }
        add("instances", Gson().toJsonTree(safe).asJsonArray)
    }
    printResponse(CliResponse("instance", true, data = data))
    return 0
}

private fun selectDescriptor(args: ParsedCliArgs): CliInstanceDescriptor {
    val explicitEndpoint = args.option("endpoint")
        ?: System.getenv("EMMY_DEBUG_ENDPOINT")?.takeIf { it.isNotBlank() }
    val explicitToken = args.option("token-file")
    if (explicitEndpoint != null) {
        return CliInstanceDescriptor(
            ideaInstanceId = args.option("instance") ?: "explicit",
            pid = ProcessHandle.current().pid(),
            product = "unknown",
            endpoint = explicitEndpoint,
            startedAt = "",
            tokenFile = explicitToken ?: throw IllegalArgumentException("--token-file is required with --endpoint")
        )
    }
    val descriptors = CliInstanceDirectory.descriptorFiles().mapNotNull(CliInstanceDirectory::read)
    val requested = args.option("instance")
    val matching = if (requested == null) descriptors else descriptors.filter { it.ideaInstanceId == requested }
    if (matching.isEmpty()) {
        throw GatewayClientException("INSTANCE_NOT_FOUND", "no IDEA instance descriptor was found")
    }
    if (matching.size > 1) {
        throw IllegalArgumentException("multiple IDEA instances found; specify --instance")
    }
    return matching.single()
}

private fun dispatch(client: GatewayClient, command: String, context: CommandContext): Int {
    val args = context.args
    val subcommand = args.positionals.getOrNull(1)?.lowercase()
    val operation = when (command) {
        "target" -> when (subcommand) { "list" -> CliOperations.TARGET_LIST; "status" -> CliOperations.TARGET_STATUS; else -> throw IllegalArgumentException("target requires list or status") }
        "vm" -> when (subcommand) { "list" -> CliOperations.VM_LIST; else -> throw IllegalArgumentException("vm requires list") }
        "stack" -> CliOperations.STACK
        "scopes" -> CliOperations.SCOPES
        "variables" -> CliOperations.VARIABLES
        "evaluate", "eval" -> CliOperations.EVALUATE
        "control" -> controlOperation(args.option("action"))
        "pause" -> CliOperations.PAUSE
        "continue", "resume" -> CliOperations.CONTINUE
        "step" -> controlOperation(args.option("action") ?: "stepOver")
        "breakpoint", "bp" -> when (subcommand) { "list" -> CliOperations.BREAKPOINT_LIST; "add" -> CliOperations.BREAKPOINT_ADD; "remove" -> CliOperations.BREAKPOINT_REMOVE; else -> throw IllegalArgumentException("breakpoint requires list, add or remove") }
        "probe" -> when (subcommand) {
            "run" -> CliOperations.PROBE_RUN
            "remove" -> CliOperations.PROBE_REMOVE
            "status" -> CliOperations.PROBE_STATUS
            "list" -> CliOperations.PROBE_LIST
            else -> throw IllegalArgumentException("probe requires run, remove, status or list")
        }
        "wait" -> CliOperations.WAIT
        "cancel" -> CliOperations.CANCEL
        "lease" -> when (subcommand) { "acquire" -> CliOperations.LEASE_ACQUIRE; "heartbeat" -> CliOperations.LEASE_HEARTBEAT; "release" -> CliOperations.LEASE_RELEASE; else -> throw IllegalArgumentException("lease requires acquire, heartbeat or release") }
        else -> throw IllegalArgumentException("unknown command '$command'\n${usage()}")
    }
    val request = buildRequest(operation, context)
    if (operation == CliOperations.WAIT) {
        var clientForShutdown: GatewayClient? = client
        val hook = Thread {
            runCatching { clientForShutdown?.cancel(request.targetId, request.requestId, context.clientId) }
        }
        Runtime.getRuntime().addShutdownHook(hook)
        return try {
            val responses = client.stream(request) { printResponse(it) }
            val terminal = responses.lastOrNull { it.done == true }
                ?: throw IllegalStateException("wait stream ended without terminal response")
            if (terminal.ok) 0 else exitCodeFor(terminal.error?.code)
        } finally {
            clientForShutdown = null
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }
    val response = client.request(request)
    printResponse(response)
    return if (response.ok) 0 else exitCodeFor(response.error?.code)
}

private fun controlOperation(action: String?): String = when (action?.lowercase()) {
    "pause", "break" -> CliOperations.PAUSE
    "continue", "resume" -> CliOperations.CONTINUE
    "stepin", "step-in", "in" -> CliOperations.STEP_IN
    "stepover", "step-over", "over" -> CliOperations.STEP_OVER
    "stepout", "step-out", "out" -> CliOperations.STEP_OUT
    else -> throw IllegalArgumentException("--action must be pause, continue, stepIn, stepOver or stepOut")
}

private fun buildRequest(operation: String, context: CommandContext): CliRequest {
    val args = context.args
    val payload = JsonObject()
    require(!(args.has("timeout-ms") && args.has("timeout"))) { "--timeout-ms conflicts with --timeout" }
    require(!(args.has("hit-limit") && args.has("hit-count"))) { "--hit-limit conflicts with --hit-count" }
    require(!(args.has("lease") && args.has("lease-id"))) { "--lease conflicts with --lease-id" }
    args.option("arguments")?.let { raw: String ->
        val parsed = JsonParser.parseString(raw)
        require(parsed.isJsonObject) { "--arguments must be a JSON object" }
        parsed.asJsonObject.entrySet().forEach { entry -> payload.add(entry.key, entry.value) }
    }
    args.option("target")?.let { payload.addProperty("targetId", it) }
    args.option("vm")?.let { payload.addProperty("vmId", it) }
    args.option("pause")?.let { payload.addProperty("pauseId", parseLongOption(it, "pause", 1L)) }
    args.option("frame")?.let { payload.addProperty("frameId", it) }
    args.option("thread")?.let { payload.addProperty("threadId", it) }
    args.option("path")?.let { payload.addProperty("path", it) }
    args.option("variables-reference")?.let { payload.addProperty("variablesReference", it) }
    args.option("expression")?.let { payload.addProperty("expression", it) }
    args.option("policy")?.let { payload.addProperty("policy", it) }
    args.option("timeout-ms")?.let { payload.addProperty("timeoutMillis", parseLongOption(it, "timeout-ms", 1L)) }
    args.option("timeout")?.let { payload.addProperty("timeoutMillis", parseDurationMillis(it)) }
    args.option("limit")?.let { payload.addProperty("limit", parseIntOption(it, "limit", 1)) }
    args.option("cursor")?.let { payload.addProperty("cursor", parseLongOption(it, "cursor", 0L)) }
    args.option("lease")?.let { payload.addProperty("leaseId", it) }
    args.option("lease-id")?.let { payload.addProperty("leaseId", it) }
    args.option("probe-id")?.let { payload.addProperty("probeId", it) }
    args.option("breakpoint-id")?.let { payload.addProperty("breakpointId", it) }
    args.option("line")?.let { payload.addProperty("line", parseIntOption(it, "line", 1)) }
    args.option("condition")?.let { payload.addProperty("condition", it) }
    args.option("log-message")?.let { payload.addProperty("logMessage", it) }
    args.option("hit-condition")?.let { payload.addProperty("hitCondition", it) }
    args.option("scope")?.let { payload.addProperty("scope", it) }
    args.option("hit-limit")?.let { payload.addProperty("hitLimit", parseIntOption(it, "hit-limit", 1)) }
    args.option("hit-count")?.let { payload.addProperty("hitLimit", parseIntOption(it, "hit-count", 1)) }
    args.option("max-depth")?.let { payload.addProperty("maxDepth", parseIntOption(it, "max-depth", 0)) }
    args.option("max-nodes")?.let { payload.addProperty("maxNodes", parseIntOption(it, "max-nodes", 1)) }
    args.option("max-bytes")?.let { payload.addProperty("maxBytes", parseIntOption(it, "max-bytes", 1)) }
    if (args.has("auto-continue")) payload.addProperty("autoContinue", true)
    if (args.has("capture")) {
        payload.add("captures", Gson().toJsonTree(args.captures))
    }
    args.option("source-identity")?.let {
        val source = JsonParser.parseString(it)
        require(source.isJsonObject) { "--source-identity must be a JSON object" }
        payload.add("sourceIdentity", source.asJsonObject)
    }
    args.option("file")?.let { path ->
        require(!payload.has("sourceIdentity")) { "--file conflicts with --source-identity" }
        payload.add("sourceIdentity", JsonObject().apply {
            addProperty("uri", Path.of(path).toAbsolutePath().normalize().toUri().toString())
            addProperty("canonicalPath", Path.of(path).toAbsolutePath().normalize().toString())
            // 本地文件存在不能证明宿主加载了同一份字节。
            addProperty("verified", false)
        })
    }
    if (operation == CliOperations.BREAKPOINT_REMOVE) {
        val ids = args.option("breakpoint-ids")?.split(',')?.filter(String::isNotBlank) ?: emptyList()
        payload.add("breakpointIds", Gson().toJsonTree(ids))
    }
    args.option("breakpoints")?.let { raw ->
        val parsed = JsonParser.parseString(raw)
        require(parsed.isJsonArray) { "--breakpoints must be a JSON array" }
        payload.add("breakpoints", parsed.asJsonArray)
    }
    if (operation == CliOperations.BREAKPOINT_ADD && !payload.has("breakpoints")) {
        val sourceIdentity = payload.get("sourceIdentity")
            ?: throw IllegalArgumentException("--source-identity is required")
        val line = payload.get("line") ?: throw IllegalArgumentException("--line is required")
        val item = JsonObject().apply {
            args.option("breakpoint-id")?.let { addProperty("breakpointId", it) }
            addProperty("vmId", args.option("vm") ?: throw IllegalArgumentException("--vm is required"))
            add("sourceIdentity", sourceIdentity)
            add("line", line)
            args.option("condition")?.let { addProperty("condition", it) }
        }
        payload.add("breakpoints", Gson().toJsonTree(listOf(item)).asJsonArray)
    }
    if (operation == CliOperations.CANCEL) {
        val cancelledId = args.option("cancel-request")
            ?: throw IllegalArgumentException("--cancel-request is required")
        payload.addProperty("waitRequestId", cancelledId)
    }
    if (operation == CliOperations.WAIT) {
        args.option("cursor")?.let { payload.addProperty("cursor", parseLongOption(it, "cursor", 0L)) }
    }
    return CliRequest(
        requestId = context.requestId,
        operation = operation,
        targetId = args.option("target"),
        arguments = payload,
        cursor = args.option("cursor")?.let { parseLongOption(it, "cursor", 0L) },
        clientId = context.clientId,
        leaseId = args.option("lease") ?: args.option("lease-id"),
        deadlineMillis = args.option("deadline-ms")?.let { parseLongOption(it, "deadline-ms", 1L) },
        vmId = args.option("vm")
    )
}

private fun readToken(path: Path): String {
    require(Files.isRegularFile(path)) { "token file does not exist" }
    require(!Files.isSymbolicLink(path)) { "token file must not be a symbolic link" }
    val size = Files.size(path)
    require(size in 1..4096) { "token file has an invalid size" }
    val token = Files.readString(path, StandardCharsets.UTF_8).trim()
    require(token.isNotEmpty()) { "token file is empty" }
    return token
}

private fun parseLongOption(value: String, name: String, minimum: Long): Long {
    val parsed = value.toLongOrNull() ?: throw IllegalArgumentException("invalid --$name")
    require(parsed >= minimum) { "--$name must be at least $minimum" }
    return parsed
}

private fun parseIntOption(value: String, name: String, minimum: Int): Int {
    val parsed = value.toIntOrNull() ?: throw IllegalArgumentException("invalid --$name")
    require(parsed >= minimum) { "--$name must be at least $minimum" }
    return parsed
}

private fun parseDurationMillis(value: String): Long {
    val match = Regex("^([0-9]+)(ms|s|m)?$", RegexOption.IGNORE_CASE).matchEntire(value.trim())
        ?: throw IllegalArgumentException("invalid --timeout")
    val amount = match.groupValues[1].toLongOrNull() ?: throw IllegalArgumentException("invalid --timeout")
    val multiplier = when (match.groupValues[2].lowercase()) {
        "m" -> 60_000L
        "s", "" -> if (match.groupValues[2].isEmpty()) 1L else 1_000L
        "ms" -> 1L
        else -> throw IllegalArgumentException("invalid --timeout")
    }
    return Math.multiplyExact(amount, multiplier).also {
        require(it > 0) { "--timeout must be positive" }
    }
}

private fun printResponse(response: CliResponse) {
    try {
        println(CliJsonLines.encode(response))
    } catch (error: CliProtocolException) {
        // A response that cannot be represented on the wire is replaced by a
        // small, machine-readable terminal error. Diagnostics stay on stderr.
        println(CliJsonLines.encode(CliJsonLines.error(
            response.requestId, CliErrorCodes.RESPONSE_TOO_LARGE,
            "response exceeds the protocol size limit"
        )))
    }
}

private fun printError(code: String, message: String, retryable: Boolean = false) {
    System.err.println("emmy-debug: [$code] $message")
    printResponse(CliJsonLines.error("", code, message, retryable))
}

private fun exitCodeFor(code: String?): Int = when (code) {
    "INSTANCE_NOT_FOUND", "GATEWAY_UNAVAILABLE" -> 3
    CliErrorCodes.TARGET_BUSY -> 8
    CliErrorCodes.NOT_AUTHORIZED, CliErrorCodes.LEASE_REQUIRED, CliErrorCodes.LEASE_EXPIRED,
    CliErrorCodes.EVALUATION_DENIED, CliErrorCodes.EVALUATION_LIMIT_EXCEEDED,
    CliErrorCodes.CANCEL_UNSUPPORTED -> 6
    CliErrorCodes.TARGET_NOT_FOUND, CliErrorCodes.VM_NOT_FOUND, CliErrorCodes.AMBIGUOUS_VM -> 4
    CliErrorCodes.STALE_PAUSE_REFERENCE, CliErrorCodes.EVENT_CURSOR_EXPIRED, CliErrorCodes.TIMEOUT -> 5
    CliErrorCodes.INVALID_ARGUMENT, CliErrorCodes.INVALID_REQUEST, CliErrorCodes.UNKNOWN_OPERATION -> 2
    CliErrorCodes.RESPONSE_TOO_LARGE, CliErrorCodes.SERVER_CLOSED, CliErrorCodes.RATE_LIMITED -> 7
    else -> 7
}

private fun usage(): String = """
Usage: emmy-debug <instance|target|vm|stack|scopes|variables|evaluate|control|pause|continue|step|breakpoint|probe|wait|cancel|lease> [options]
Global options: --instance ID --endpoint URL --token-file FILE --client ID --request-id ID
                --target ID --vm ID --pause ID --frame ID --deadline-ms N --json
Examples:
  emmy-debug instance
  emmy-debug target list
  emmy-debug vm list --target target-1
  emmy-debug variables --target target-1 --vm vm-1 --pause 2 --frame frame-2-0
  emmy-debug wait --target target-1 --cursor 0
""".trimIndent()

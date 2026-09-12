package com.tang.intellij.lua.debugger.cli

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

const val CLI_SCHEMA_VERSION = 1
const val CLI_MAX_LINE_BYTES = 256 * 1024

/** Protocol-level failure that can be rendered without losing its stable code. */
class CliProtocolException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

data class CliRequest(
    val requestId: String,
    val operation: String,
    val targetId: String? = null,
    val arguments: JsonObject = JsonObject(),
    val cursor: Long? = null,
    val clientId: String? = null,
    val leaseId: String? = null,
    val deadlineMillis: Long? = null,
    /** Optional shorthand accepted by the wire protocol for VM-scoped calls. */
    val vmId: String? = null
)

data class CliError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: JsonObject? = null
)

data class CliResponse(
    val requestId: String,
    val ok: Boolean,
    val data: JsonObject? = null,
    val error: CliError? = null,
    val event: String? = null,
    val done: Boolean? = null,
    val cursor: Long? = null
)

data class CliInstanceDescriptor(
    val schemaVersion: Int = CLI_SCHEMA_VERSION,
    val ideaInstanceId: String,
    val pid: Long,
    val product: String,
    val endpoint: String,
    val startedAt: String,
    val tokenFile: String
)

/** Shared, dependency-free discovery rules used by IDEA and emmy-debug. */
object CliInstanceDirectory {
    const val PROPERTY = "emmy.debug.instanceDir"
    const val ENVIRONMENT = "EMMY_DEBUG_INSTANCE_DIR"
    private const val DEFAULT_FOLDER = ".emmy-debug"
    private const val DEFAULT_INSTANCES = "instances"

    fun root(explicit: Path? = null): Path = explicit
        ?: System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: System.getenv(ENVIRONMENT)?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), DEFAULT_FOLDER, DEFAULT_INSTANCES)

    /** Read-only fallback for descriptors written by older plugin versions. */
    fun legacyRoot(): Path = Path.of(
        System.getProperty("idea.system.path", Path.of(System.getProperty("java.io.tmpdir"), "idea-system").toString()),
        "emmylua-cli"
    )

    fun descriptorFiles(explicit: Path? = null): List<Path> {
        val roots = linkedSetOf(root(explicit)).apply {
            if (explicit == null) add(legacyRoot())
        }
        return roots.flatMap { directory ->
            if (!Files.isDirectory(directory)) emptyList()
            else Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") }.toList()
            }
        }.distinct().sortedBy { it.toString() }
    }

    fun read(path: Path): CliInstanceDescriptor? = runCatching {
        if (!Files.isRegularFile(path)) return@runCatching null
        val descriptor = Gson().fromJson(Files.readString(path), CliInstanceDescriptor::class.java)
        require(descriptor.schemaVersion == CLI_SCHEMA_VERSION)
        require(descriptor.ideaInstanceId.isNotBlank() && descriptor.endpoint.isNotBlank())
        require(descriptor.pid > 0)
        descriptor
    }.getOrNull()

    fun writeAtomic(path: Path, descriptor: CliInstanceDescriptor) {
        Files.createDirectories(requireNotNull(path.parent))
        val temp = path.resolveSibling(".${path.fileName}.tmp-${ProcessHandle.current().pid()}")
        Files.writeString(temp, Gson().toJson(descriptor), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        try {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

data class CliVmSummary(
    val vmId: String,
    val generation: Long,
    val displayName: String,
    val state: String,
    val luaVersion: String? = null,
    val discovery: String = "UNKNOWN",
    val activePauseId: Long? = null,
    val connectionEpoch: Long? = null,
    val contextGeneration: Long? = null,
    val sourceEpoch: Long? = null
)

data class CliTargetSummary(
    val targetId: String,
    val projectName: String,
    val state: String,
    val agentReady: Boolean,
    val vms: List<CliVmSummary> = emptyList(),
    /** True only when at least one VM passed the snapshot fence and is usable. */
    val vmReady: Boolean = false
)

object CliJsonLines {
    private val gson = Gson()

    /**
     * Reads one UTF-8 line without allowing an unbounded BufferedReader to
     * allocate memory before the protocol limit is checked.
     */
    fun readBoundedLine(input: InputStream, maxBytes: Int = CLI_MAX_LINE_BYTES): String? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val bytes = ByteArrayOutputStream(minOf(maxBytes, 4096))
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                break
            }
            if (value == '\n'.code) break
            if (bytes.size() >= maxBytes) {
                throw CliProtocolException(
                    CliErrorCodes.INVALID_ARGUMENT,
                    "CLI line exceeds $maxBytes bytes"
                )
            }
            bytes.write(value)
        }
        val raw = bytes.toByteArray()
        val length = if (raw.lastOrNull()?.toInt() == '\r'.code) raw.size - 1 else raw.size
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw, 0, length)).toString()
        } catch (error: CharacterCodingException) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "CLI line is not valid UTF-8")
        }
    }

    fun decodeRequest(line: String): CliRequest {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "CLI line must not contain a newline")
        }
        if (line.toByteArray(StandardCharsets.UTF_8).size > CLI_MAX_LINE_BYTES) {
            throw CliProtocolException(
                CliErrorCodes.INVALID_ARGUMENT,
                "CLI request exceeds ${CLI_MAX_LINE_BYTES} bytes"
            )
        }
        val json = try {
            JsonParser.parseString(line)
        } catch (error: Throwable) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "malformed CLI JSON", cause = error)
        }
        if (!json.isJsonObject) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "CLI request must be a JSON object")
        }
        val obj = json.asJsonObject
        val requestId = obj.string("requestId") ?: ""
        val operation = obj.string("operation") ?: ""
        val targetId = obj.string("targetId")
        val cursor = obj.long("cursor")
        val clientId = obj.string("clientId")
        val leaseId = obj.string("leaseId")
        val deadlineMillis = obj.long("deadlineMillis")
        val vmId = obj.string("vmId")
        val arguments = obj.get("arguments")?.let {
            require(it.isJsonObject) { "arguments must be a JSON object" }
            it.asJsonObject
        } ?: JsonObject()
        val request = CliRequest(
            requestId = requestId,
            operation = operation,
            targetId = targetId,
            arguments = arguments,
            cursor = cursor,
            clientId = clientId,
            leaseId = leaseId,
            deadlineMillis = deadlineMillis,
            vmId = vmId
        )
        if (request.requestId.isBlank() || request.requestId.length > 128) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "invalid requestId")
        }
        if (request.operation.isBlank() || request.operation.length > 128) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "invalid operation")
        }
        if (request.targetId != null && request.targetId.length > 256) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "invalid targetId")
        }
        if (request.vmId != null && (request.vmId.isBlank() || request.vmId.length > 256)) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "invalid vmId")
        }
        if (request.clientId != null && (request.clientId.isBlank() || request.clientId.length > 128)) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "invalid clientId")
        }
        if (request.leaseId != null && (request.leaseId.isBlank() || request.leaseId.length > 128)) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "invalid leaseId")
        }
        if (request.cursor != null && request.cursor < 0) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "invalid cursor")
        }
        if (request.deadlineMillis != null && request.deadlineMillis <= 0) {
            throw CliProtocolException(CliErrorCodes.INVALID_ARGUMENT, "invalid deadlineMillis")
        }
        return request
    }

    fun encode(value: Any): String {
        val line = gson.toJson(value)
        if (line.toByteArray(StandardCharsets.UTF_8).size > CLI_MAX_LINE_BYTES) {
            throw CliProtocolException(
                CliErrorCodes.RESPONSE_TOO_LARGE,
                "CLI response exceeds ${CLI_MAX_LINE_BYTES} bytes"
            )
        }
        return line
    }

    fun error(requestId: String, code: String, message: String, retryable: Boolean = false): CliResponse {
        return CliResponse(requestId, ok = false, error = CliError(code, message, retryable))
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$name must be a string" }
        it.asString
    }

    private fun JsonObject.long(name: String): Long? = get(name)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "$name must be an integer" }
        val text = it.asJsonPrimitive.asString
        require(text.matches(Regex("-?[0-9]+"))) { "$name must be an integer" }
        runCatching { text.toLong() }.getOrElse {
            throw IllegalArgumentException("$name must be an integer", it)
        }
    }
}

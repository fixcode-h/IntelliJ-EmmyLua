package com.tang.intellij.lua.debugger.cli

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets

const val CLI_SCHEMA_VERSION = 1
const val CLI_MAX_LINE_BYTES = 256 * 1024

data class CliRequest(
    val requestId: String,
    val operation: String,
    val targetId: String? = null,
    val arguments: JsonObject = JsonObject(),
    val cursor: Long? = null
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
    val done: Boolean? = null
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

data class CliVmSummary(
    val vmId: String,
    val generation: Long,
    val displayName: String,
    val state: String,
    val luaVersion: String? = null,
    val discovery: String = "UNKNOWN",
    val activePauseId: Long? = null
)

object CliJsonLines {
    private val gson = Gson()

    fun decodeRequest(line: String): CliRequest {
        require(line.indexOf('\n') < 0 && line.indexOf('\r') < 0) { "CLI line must not contain a newline" }
        require(line.toByteArray(StandardCharsets.UTF_8).size <= CLI_MAX_LINE_BYTES) {
            "CLI request exceeds ${CLI_MAX_LINE_BYTES} bytes"
        }
        val request = gson.fromJson(line, CliRequest::class.java)
        require(request.requestId.isNotBlank() && request.requestId.length <= 128) { "invalid requestId" }
        require(request.operation.isNotBlank() && request.operation.length <= 128) { "invalid operation" }
        require(request.targetId == null || request.targetId.length <= 256) { "invalid targetId" }
        return request
    }

    fun encode(value: Any): String {
        val line = gson.toJson(value)
        require(line.toByteArray(StandardCharsets.UTF_8).size <= CLI_MAX_LINE_BYTES) {
            "CLI response exceeds ${CLI_MAX_LINE_BYTES} bytes"
        }
        return line
    }

    fun error(requestId: String, code: String, message: String, retryable: Boolean = false): CliResponse {
        return CliResponse(requestId, ok = false, error = CliError(code, message, retryable))
    }
}

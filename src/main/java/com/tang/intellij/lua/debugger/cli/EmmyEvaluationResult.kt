package com.tang.intellij.lua.debugger.cli

import com.google.gson.JsonObject
import com.tang.intellij.lua.debugger.emmy.EmmyV2Envelope

/** Copies a bounded native VALUE_PATH result without retaining native cache references. */
object EmmyEvaluationResult {
    fun decode(envelope: EmmyV2Envelope): Result<CliCapturedValue> = runCatching {
        val payload = envelope.payload
        if (envelope.ok == false || payload?.get("success")?.asBoolean == false) {
            error(envelope.error?.code ?: payload?.get("error")?.asString ?: CliErrorCodes.EVALUATION_DENIED)
        }
        val root = payload?.getAsJsonObject("value") ?: error("EVAL_EMPTY_RESPONSE")
        var nodes = 0
        var bytes = 0
        fun node(value: JsonObject, depth: Int): CliVariableSnapshot {
            check(depth <= 3 && ++nodes <= 100) { CliErrorCodes.EVALUATION_LIMIT_EXCEEDED }
            val name = value.get("name")?.asString ?: ""
            val type = value.get("valueTypeName")?.asString ?: "unknown"
            val display = value.get("value")?.asString ?: ""
            bytes += name.toByteArray(Charsets.UTF_8).size + display.toByteArray(Charsets.UTF_8).size
            check(bytes <= 64 * 1024) { CliErrorCodes.EVALUATION_LIMIT_EXCEEDED }
            val children = value.getAsJsonArray("children")?.map { node(it.asJsonObject, depth + 1) }.orEmpty()
            return CliVariableSnapshot(name, type, display, childCount = children.size,
                truncated = value.get("truncated")?.asBoolean == true || children.any { it.truncated }, children = children)
        }
        val value = node(root, 0)
        CliCapturedValue(payload.get("expr")?.asString ?: "", true, value.type, value.display,
            truncated = value.truncated, children = value.children)
    }
}

package com.tang.intellij.lua.debugger.cli

import com.google.gson.JsonArray
import com.google.gson.JsonObject

data class CliTargetSummary(
    val targetId: String,
    val projectName: String,
    val state: String,
    val agentReady: Boolean,
    val vms: List<CliVmSummary> = emptyList()
)

fun interface CliTargetProvider {
    fun targets(): List<CliTargetSummary>
}

/** Read-only request dispatcher. It has no dependency on XDebugger/Swing objects. */
class CliGatewayService(
    private val provider: CliTargetProvider,
    private val trustedProject: () -> Boolean = { true }
) {
    fun handle(request: CliRequest): CliResponse {
        return try {
            when (request.operation) {
                "target.list" -> ok(request, targetList())
                "target.status" -> ok(request, targetStatus(request.targetId))
                "vm.list" -> ok(request, vmList(request.targetId))
                else -> CliJsonLines.error(request.requestId, "UNKNOWN_OPERATION", "unsupported read-only operation")
            }
        } catch (error: CliGatewayException) {
            CliJsonLines.error(request.requestId, error.code, error.message ?: error.code, error.retryable)
        } catch (error: Throwable) {
            CliJsonLines.error(request.requestId, "INTERNAL_ERROR", error.message ?: "internal error")
        }
    }

    private fun targetList(): JsonObject {
        val data = JsonObject()
        val targets = JsonArray()
        provider.targets().forEach { target ->
            val item = JsonObject()
            item.addProperty("targetId", target.targetId)
            item.addProperty("projectName", if (trustedProject()) target.projectName else "<redacted>")
            item.addProperty("state", target.state)
            item.addProperty("agentReady", target.agentReady)
            targets.add(item)
        }
        data.add("targets", targets)
        return data
    }

    private fun targetStatus(targetId: String?): JsonObject {
        val target = findTarget(targetId)
        val data = JsonObject()
        data.addProperty("targetId", target.targetId)
        data.addProperty("projectName", if (trustedProject()) target.projectName else "<redacted>")
        data.addProperty("state", target.state)
        data.addProperty("agentReady", target.agentReady)
        data.addProperty("vmCount", target.vms.size)
        return data
    }

    private fun vmList(targetId: String?): JsonObject {
        val target = findTarget(targetId)
        val data = JsonObject()
        data.addProperty("targetId", target.targetId)
        val vms = JsonArray()
        target.vms.forEach { vm ->
            val item = JsonObject()
            item.addProperty("vmId", vm.vmId)
            item.addProperty("generation", vm.generation)
            item.addProperty("displayName", vm.displayName)
            item.addProperty("state", vm.state)
            vm.luaVersion?.let { item.addProperty("luaVersion", it) }
            item.addProperty("discovery", vm.discovery)
            vm.activePauseId?.let { item.addProperty("activePauseId", it) }
            vms.add(item)
        }
        data.add("vms", vms)
        return data
    }

    private fun findTarget(targetId: String?): CliTargetSummary {
        if (targetId.isNullOrBlank()) throw CliGatewayException("TARGET_REQUIRED", "targetId is required")
        return provider.targets().firstOrNull { it.targetId == targetId }
            ?: throw CliGatewayException("TARGET_NOT_FOUND", "target does not exist")
    }

    private fun ok(request: CliRequest, data: JsonObject): CliResponse =
        CliResponse(requestId = request.requestId, ok = true, data = data)
}

class CliGatewayException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false
) : RuntimeException(message)

package com.tang.intellij.lua.debugger.emmy

import com.google.gson.Gson
import com.google.gson.JsonObject

/** Reserved wire id for versioned envelopes. The legacy ids 0..17 are immutable. */
const val EMMY_V2_ENVELOPE_WIRE_ID: Int = 18

private object EmmyProtocolV2Codec {
    val gson: Gson = Gson()
}

interface EmmyV2Dto {
    fun toJson(): String = EmmyProtocolV2Codec.gson.toJson(this)
}

data class EmmyV2Target(
    val vmId: String? = null,
    val threadId: String? = null,
    val pauseId: Long? = null,
    val frameId: String? = null
) : EmmyV2Dto

data class EmmyV2Error(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: JsonObject? = null
) : EmmyV2Dto

data class EmmyV2Envelope(
    val cmd: Int = EMMY_V2_ENVELOPE_WIRE_ID,
    val protocolVersion: Int = 2,
    val kind: String,
    val type: String,
    val requestId: String? = null,
    val agentSessionId: String? = null,
    val connectionEpoch: Long? = null,
    val contextGeneration: Long? = null,
    val sourceEpoch: Long? = null,
    val eventSeq: Long? = null,
    val target: EmmyV2Target? = null,
    val ok: Boolean? = null,
    val error: EmmyV2Error? = null,
    val payload: JsonObject? = null
) : EmmyV2Dto {
    companion object {
        fun fromJson(json: String): EmmyV2Envelope {
            return EmmyProtocolV2Codec.gson.fromJson(json, EmmyV2Envelope::class.java)
        }
    }
}

data class VmDto(
    val vmId: String,
    val generation: Long,
    val displayName: String,
    val state: String,
    val luaVersion: String?,
    val discovery: String,
    val diagnosticStateAddress: String? = null,
    val contextGeneration: Long? = null,
    val sourceEpoch: Long? = null
) : EmmyV2Dto

data class VmSnapshotDto(
    val snapshotEventSeq: Long,
    val vms: List<VmDto>
) : EmmyV2Dto

data class VmLifecycleDto(
    val vmId: String,
    val generation: Long,
    val previous: String?,
    val current: String,
    val reason: String? = null,
    val eventSeq: Long,
    val contextGeneration: Long? = null,
    val sourceEpoch: Long? = null
) : EmmyV2Dto

data class AgentDescribeDto(
    val agentSessionId: String,
    val protocolVersion: Int,
    val processId: Long,
    val capabilities: List<String>
) : EmmyV2Dto

data class DebugPausedDto(
    val pauseId: Long,
    val threadId: String? = null,
    val pauseScope: String = "THREAD",
    val consistency: String = "THREAD_ONLY",
    val reason: String? = null,
    val stacks: List<Stack> = emptyList()
) : EmmyV2Dto

data class DebugResumedDto(
    val pauseId: Long? = null,
    val reason: String? = null
) : EmmyV2Dto

class EmmyV2Message(val envelope: EmmyV2Envelope) : IMessage {
    override val cmd: Int = EMMY_V2_ENVELOPE_WIRE_ID

    override fun toJSON(): String = envelope.toJson()
}

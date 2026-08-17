package com.tang.intellij.lua.debugger.luapanda

import com.google.gson.Gson

data class EncodedLuaPandaMessage(val json: String, val payload: String)

class LuaPandaWireCodec(private val gson: Gson = Gson()) {
    fun encode(message: LuaPandaMessage): EncodedLuaPandaMessage = encodeJson(gson.toJson(message))

    fun encodeCommand(cmd: String, callbackId: String, info: Any?): EncodedLuaPandaMessage {
        val message = linkedMapOf<String, Any?>(
            "cmd" to cmd,
            "callbackId" to callbackId
        )
        if (info != null) message["info"] = info
        return encodeJson(gson.toJson(message))
    }

    fun decode(payload: String): LuaPandaMessage {
        val json = payload.trimEnd().removeSuffix(PROTOCOL_SEPARATOR).trimEnd()
        return gson.fromJson(json, LuaPandaMessage::class.java)
    }

    fun extractJson(payload: String): String = payload.trimEnd().removeSuffix(PROTOCOL_SEPARATOR).trimEnd()

    private fun encodeJson(json: String): EncodedLuaPandaMessage =
        EncodedLuaPandaMessage(json, "$json $PROTOCOL_SEPARATOR\n")

    companion object {
        const val PROTOCOL_SEPARATOR = "|*|"
    }
}

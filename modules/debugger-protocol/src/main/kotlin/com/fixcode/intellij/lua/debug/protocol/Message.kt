package com.fixcode.intellij.lua.debug.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

/**
 * v3 的三种消息。request/response 用 `id` 一一对应（取代旧协议靠命令号猜回调），
 * event 带单调递增的 `seq`，便于排查丢事件。
 */
sealed class ProtocolMessage {
    abstract val frameType: FrameType

    data class Request(val id: Long, val command: String, val args: JsonObject = JsonObject()) : ProtocolMessage() {
        override val frameType: FrameType get() = FrameType.REQUEST
    }

    data class Response(
        val id: Long,
        val ok: Boolean,
        val body: JsonObject = JsonObject(),
        val error: ProtocolError? = null
    ) : ProtocolMessage() {
        override val frameType: FrameType get() = FrameType.RESPONSE

        companion object {
            fun success(id: Long, body: JsonObject = JsonObject()) = Response(id, true, body)
            fun failure(id: Long, code: String, message: String) = Response(id, false, JsonObject(), ProtocolError(code, message))
        }
    }

    data class Event(val seq: Long, val event: String, val body: JsonObject = JsonObject()) : ProtocolMessage() {
        override val frameType: FrameType get() = FrameType.EVENT
    }
}

data class ProtocolError(val code: String, val message: String)

/**
 * 消息 ⇄ 帧。目前只实现 JSON；CBOR 帧（type 字节带 0x10 位）在握手协商 `cbor` 能力后才会出现，
 * 两端用同一套 DTO，切换编码不影响上层。
 */
object MessageCodec {
    fun encode(message: ProtocolMessage): Frame {
        val json = JsonObject()
        when (message) {
            is ProtocolMessage.Request -> {
                json.addProperty("id", message.id)
                json.addProperty("command", message.command)
                json.add("args", message.args)
            }
            is ProtocolMessage.Response -> {
                json.addProperty("id", message.id)
                json.addProperty("ok", message.ok)
                json.add("body", message.body)
                message.error?.let { error ->
                    json.add("error", JsonObject().apply {
                        addProperty("code", error.code)
                        addProperty("message", error.message)
                    })
                }
            }
            is ProtocolMessage.Event -> {
                json.addProperty("seq", message.seq)
                json.addProperty("event", message.event)
                json.add("body", message.body)
            }
        }
        return Frame(message.frameType, json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(frame: Frame): ProtocolMessage {
        if (frame.cbor) throw ProtocolException("收到 CBOR 帧，但本端没有协商 cbor 能力")
        val root: JsonElement = try {
            JsonParser().parse(String(frame.payload, StandardCharsets.UTF_8))
        } catch (error: JsonParseException) {
            throw ProtocolException("帧内容不是合法 JSON：${error.message}", error)
        }
        if (!root.isJsonObject) throw ProtocolException("帧内容必须是 JSON 对象")
        val json = root.asJsonObject
        return try {
            when (frame.type) {
                FrameType.REQUEST -> ProtocolMessage.Request(
                    json.reqLong("id"), json.reqString("command"), json.optObject("args") ?: JsonObject()
                )
                FrameType.RESPONSE -> {
                    val error = json.optObject("error")?.let {
                        ProtocolError(it.optString("code") ?: ErrorCodes.INTERNAL, it.optString("message") ?: "")
                    }
                    ProtocolMessage.Response(
                        json.reqLong("id"), json.optBoolean("ok") ?: (error == null), json.optObject("body") ?: JsonObject(), error
                    )
                }
                FrameType.EVENT -> ProtocolMessage.Event(
                    json.optLong("seq") ?: 0, json.reqString("event"), json.optObject("body") ?: JsonObject()
                )
            }
        } catch (error: DtoException) {
            throw ProtocolException(error.message ?: "消息字段错误", error)
        }
    }
}

package com.fixcode.intellij.lua.debug.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * DTO 手写 JSON 读写的小工具。
 *
 * 不用 Gson 反射直接映射 data class：Gson 绕过构造函数，缺字段时 Kotlin 的非空字段会变成 null、默认值丢失，
 * 错误要到很远的地方才炸。手写读写能在解码时就报出“哪个字段缺了”。
 */
class DtoException(message: String) : RuntimeException(message)

fun JsonObject.optString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.let {
    if (it.isJsonPrimitive) it.asString else throw DtoException("字段 $name 应为字符串")
}

fun JsonObject.reqString(name: String): String = optString(name) ?: throw DtoException("缺少字段 $name")

fun JsonObject.optLong(name: String): Long? = get(name)?.takeUnless { it.isJsonNull }?.let {
    try {
        it.asLong
    } catch (error: RuntimeException) {
        throw DtoException("字段 $name 应为整数")
    }
}

fun JsonObject.reqLong(name: String): Long = optLong(name) ?: throw DtoException("缺少字段 $name")

fun JsonObject.optInt(name: String): Int? = optLong(name)?.toInt()

fun JsonObject.reqInt(name: String): Int = reqLong(name).toInt()

fun JsonObject.optDouble(name: String): Double? = get(name)?.takeUnless { it.isJsonNull }?.let {
    try {
        it.asDouble
    } catch (error: RuntimeException) {
        throw DtoException("字段 $name 应为数字")
    }
}

fun JsonObject.optBoolean(name: String): Boolean? = get(name)?.takeUnless { it.isJsonNull }?.let {
    if (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) it.asBoolean else throw DtoException("字段 $name 应为布尔值")
}

fun JsonObject.optObject(name: String): JsonObject? = get(name)?.takeUnless { it.isJsonNull }?.let {
    if (it.isJsonObject) it.asJsonObject else throw DtoException("字段 $name 应为对象")
}

fun JsonObject.reqObject(name: String): JsonObject = optObject(name) ?: throw DtoException("缺少字段 $name")

fun JsonObject.optArray(name: String): JsonArray? = get(name)?.takeUnless { it.isJsonNull }?.let {
    if (it.isJsonArray) it.asJsonArray else throw DtoException("字段 $name 应为数组")
}

fun <T> JsonObject.objects(name: String, read: (JsonObject) -> T): List<T> =
    optArray(name)?.map { element ->
        if (!element.isJsonObject) throw DtoException("字段 $name 的元素应为对象")
        read(element.asJsonObject)
    } ?: emptyList()

fun JsonObject.strings(name: String): List<String> = optArray(name)?.map { it.asString } ?: emptyList()

fun JsonObject.longs(name: String): List<Long> = optArray(name)?.map { it.asLong } ?: emptyList()

/** 构造 JSON：null 值不写出，减小帧体积。 */
class JsonBuilder {
    val json = JsonObject()

    fun put(name: String, value: String?) { if (value != null) json.addProperty(name, value) }
    fun put(name: String, value: Number?) { if (value != null) json.addProperty(name, value) }
    fun put(name: String, value: Boolean?) { if (value != null) json.addProperty(name, value) }
    fun put(name: String, value: JsonElement?) { if (value != null && value !is JsonNull) json.add(name, value) }
    fun put(name: String, value: JsonDto?) { if (value != null) json.add(name, value.toJson()) }

    fun putStrings(name: String, values: List<String>?) {
        if (values == null) return
        json.add(name, JsonArray().apply { values.forEach { add(it) } })
    }

    fun putLongs(name: String, values: List<Long>?) {
        if (values == null) return
        json.add(name, JsonArray().apply { values.forEach { add(it) } })
    }

    fun putObjects(name: String, values: List<JsonDto>?) {
        if (values == null) return
        json.add(name, JsonArray().apply { values.forEach { add(it.toJson()) } })
    }
}

inline fun jsonObject(block: JsonBuilder.() -> Unit): JsonObject = JsonBuilder().apply(block).json

interface JsonDto {
    fun toJson(): JsonObject
}

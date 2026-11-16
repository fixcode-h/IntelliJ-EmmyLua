package com.fixcode.intellij.lua.debugger.mcp.tools

/** 写 JSON Schema 的小 DSL，免得每个工具手拼一大坨 Map。 */
internal class SchemaBuilder {
    private val properties = LinkedHashMap<String, Any?>()
    private val required = ArrayList<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) {
        val p = linkedMapOf<String, Any?>("type" to "string", "description" to description)
        if (enum != null) p["enum"] = enum
        add(name, p, required)
    }

    fun integer(name: String, description: String, required: Boolean = false, minimum: Int? = null, maximum: Int? = null) {
        val p = linkedMapOf<String, Any?>("type" to "integer", "description" to description)
        if (minimum != null) p["minimum"] = minimum
        if (maximum != null) p["maximum"] = maximum
        add(name, p, required)
    }

    fun boolean(name: String, description: String, required: Boolean = false) =
        add(name, linkedMapOf("type" to "boolean", "description" to description), required)

    fun stringArray(name: String, description: String, required: Boolean = false) =
        add(
            name,
            linkedMapOf("type" to "array", "items" to linkedMapOf("type" to "string"), "description" to description),
            required,
        )

    fun integerArray(name: String, description: String, maxItems: Int? = null) {
        val p = linkedMapOf<String, Any?>("type" to "array", "items" to linkedMapOf("type" to "integer"), "description" to description)
        if (maxItems != null) p["maxItems"] = maxItems
        add(name, p, false)
    }

    fun stringArray(name: String, description: String, maxItems: Int) {
        add(name, linkedMapOf("type" to "array", "items" to linkedMapOf("type" to "string"), "maxItems" to maxItems, "description" to description), false)
    }

    /** 整数或字符串都接受（例如 hit_count：5 或 ">=3"）。 */
    fun integerOrString(name: String, description: String) =
        add(name, linkedMapOf("type" to listOf("integer", "string"), "description" to description), false)

    private fun add(name: String, p: Map<String, Any?>, required: Boolean) {
        properties[name] = p
        if (required) this.required += name
    }

    fun build(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("type" to "object", "properties" to properties)
        if (required.isNotEmpty()) m["required"] = required
        m["additionalProperties"] = false
        return m
    }
}

internal fun schema(block: SchemaBuilder.() -> Unit): Map<String, Any?> = SchemaBuilder().apply(block).build()

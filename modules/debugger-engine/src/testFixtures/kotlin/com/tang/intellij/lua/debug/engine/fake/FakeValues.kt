package com.tang.intellij.lua.debug.engine.fake

/** 假 runtime 里的 Lua 值。只覆盖调试器关心的部分：类型、显示文本、table 的子项。 */
sealed class FakeValue {
    abstract val type: String
    abstract fun display(): String

    data class Prim(override val type: String, val text: String) : FakeValue() {
        override fun display() = text
    }

    class Table(val entries: LinkedHashMap<String, FakeValue> = LinkedHashMap(), val typeName: String? = null) : FakeValue() {
        override val type: String get() = "table"
        val address: String = "0x" + Integer.toHexString(System.identityHashCode(this))

        override fun display(): String {
            val head = entries.entries.take(5).joinToString(", ") { (k, v) -> "$k=${if (v is Table) "{…}" else v.display()}" }
            return "{" + head + (if (entries.size > 5) ", …" else "") + "}"
        }
    }

    companion object {
        val NIL = Prim("nil", "nil")
        fun num(value: Number) = Prim("number", value.toString())
        fun str(value: String) = Prim("string", "\"$value\"")
        fun bool(value: Boolean) = Prim("boolean", value.toString())
        fun table(vararg entries: Pair<String, FakeValue>, typeName: String? = null) = Table(linkedMapOf(*entries), typeName)
        fun list(vararg items: FakeValue) = Table(LinkedHashMap(items.withIndex().associate { "[${it.index + 1}]" to it.value }))

        /** setVariable / 条件里的字面量。 */
        fun parseLiteral(text: String): FakeValue? {
            val trimmed = text.trim()
            return when {
                trimmed == "nil" -> NIL
                trimmed == "true" || trimmed == "false" -> bool(trimmed.toBoolean())
                trimmed.toLongOrNull() != null -> num(trimmed.toLong())
                trimmed.toDoubleOrNull() != null -> num(trimmed.toDouble())
                trimmed.length >= 2 && (trimmed.first() == '"' && trimmed.last() == '"' || trimmed.first() == '\'' && trimmed.last() == '\'') ->
                    str(trimmed.substring(1, trimmed.length - 1))
                else -> null
            }
        }
    }
}

/** 一帧。[locals] 的顺序就是 variables 返回的顺序。 */
data class FakeFrame(
    val name: String,
    val chunkName: String,
    val line: Int,
    val locals: LinkedHashMap<String, FakeValue> = LinkedHashMap(),
    val upvalues: LinkedHashMap<String, FakeValue> = LinkedHashMap(),
    val what: String = "Lua"
) {
    companion object {
        fun lua(name: String, chunkName: String, line: Int, vararg locals: Pair<String, FakeValue>) =
            FakeFrame(name, chunkName, line, linkedMapOf(*locals))

        fun c(name: String) = FakeFrame(name, "=[C]", -1, what = "C")
    }
}

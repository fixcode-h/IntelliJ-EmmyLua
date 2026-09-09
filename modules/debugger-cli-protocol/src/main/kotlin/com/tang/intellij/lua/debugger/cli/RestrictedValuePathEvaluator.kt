package com.tang.intellij.lua.debugger.cli

data class CliValue(
    val type: String,
    val display: String,
    val children: Map<String, CliValue> = emptyMap()
)

fun interface ValuePathResolver {
    fun resolve(segments: List<String>): CliValue?
}

class RestrictedValuePathEvaluator(
    private val maxLength: Int = 256,
    private val maxSegments: Int = 8
) {
    fun evaluate(expression: String, resolver: ValuePathResolver): Result<CliValue> {
        if (expression.length > maxLength) return Result.failure(IllegalArgumentException("EVALUATION_LIMIT_EXCEEDED"))
        val segments = parse(expression).getOrElse { return Result.failure(it) }
        return resolver.resolve(segments)?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("VALUE_NOT_FOUND"))
    }

    private fun parse(expression: String): Result<List<String>> {
        val text = expression.trim()
        if (text.isEmpty() || text.any { it in "();={}\\n\\r" }) {
            return Result.failure(IllegalArgumentException("EVALUATION_DENIED"))
        }
        val segments = mutableListOf<String>()
        var index = 0
        fun readIdentifier(): Boolean {
            if (index >= text.length || !(text[index].isLetter() || text[index] == '_')) return false
            val start = index++
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
            segments += text.substring(start, index)
            return true
        }
        if (!readIdentifier()) return Result.failure(IllegalArgumentException("EVALUATION_DENIED"))
        while (index < text.length) {
            when (text[index]) {
                '.' -> {
                    index++
                    if (!readIdentifier()) return Result.failure(IllegalArgumentException("EVALUATION_DENIED"))
                }
                '[' -> {
                    val end = text.indexOf(']', index + 1)
                    if (end < 0) return Result.failure(IllegalArgumentException("EVALUATION_DENIED"))
                    val literal = text.substring(index + 1, end).trim()
                    if (!literal.matches(Regex("[A-Za-z_][A-Za-z0-9_]*|[0-9]+|\\\"[^\\\"]*\\\""))) {
                        return Result.failure(IllegalArgumentException("EVALUATION_DENIED"))
                    }
                    segments += literal.trim('"')
                    index = end + 1
                }
                else -> return Result.failure(IllegalArgumentException("EVALUATION_DENIED"))
            }
            if (segments.size > maxSegments) return Result.failure(IllegalArgumentException("EVALUATION_LIMIT_EXCEEDED"))
        }
        if (segments.any { it.startsWith("__") }) {
            return Result.failure(IllegalArgumentException("EVALUATION_DENIED"))
        }
        return Result.success(segments)
    }
}

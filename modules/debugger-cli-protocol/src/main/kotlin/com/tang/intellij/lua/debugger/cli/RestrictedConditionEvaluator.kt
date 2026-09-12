package com.tang.intellij.lua.debugger.cli

/**
 * Deterministic boolean language used by AI probes.
 *
 * The language is intentionally smaller than Lua.  A path is parsed with the
 * same grammar as [RestrictedValuePathEvaluator] and is the only expression
 * that reaches a backend.  No Lua source, call expression, assignment,
 * metamethod, or library lookup can pass through this class.
 */
class RestrictedConditionEvaluator(
    private val maxLength: Int = 4096,
    private val maxTokens: Int = 128,
    maxPathSegments: Int = 8
) {
    private val pathEvaluator = RestrictedValuePathEvaluator(maxLength, maxPathSegments)

    fun validate(expression: String): Result<Unit> = parse(expression).map { Unit }

    /** Resolves paths one at a time and returns a boolean condition result. */
    fun evaluate(
        expression: String,
        resolver: (String) -> Result<CliCapturedValue>
    ): Result<Boolean> {
        val ast = parse(expression).getOrElse { return Result.failure(it) }
        return try {
            Result.success(evaluateBoolean(ast, resolver))
        } catch (error: Throwable) {
            // Preserve adapter error codes (for example STALE_PAUSE_REFERENCE)
            // instead of converting them to a false condition.
            Result.failure(error)
        }
    }

    private fun parse(expression: String): Result<Expr> = try {
        Result.success(Parser(expression).parse())
    } catch (error: Throwable) {
        Result.failure(IllegalArgumentException(CliErrorCodes.INVALID_CONDITION, error))
    }

    private sealed class Expr {
        data class Literal(val value: Value) : Expr()
        data class Path(val text: String) : Expr()
        data class Not(val child: Expr) : Expr()
        data class Logical(val operator: Operator, val left: Expr, val right: Expr) : Expr()
        data class Compare(val operator: String, val left: Expr, val right: Expr) : Expr()

        enum class Operator { AND, OR }
    }

    private enum class Kind { IDENTIFIER, NUMBER, STRING, OPERATOR, LEFT, RIGHT, END }

    private data class Token(val kind: Kind, val text: String)

    private enum class ValueKind { NIL, BOOLEAN, NUMBER, STRING, OTHER }

    private data class Value(val kind: ValueKind, val text: String, val type: String = "")

    private inner class Parser(private val source: String) {
        private val tokens = Lexer(source).tokenize()
        private var index = 0

        fun parse(): Expr {
            val result = parseOr()
            require(peek().kind == Kind.END) { "trailing tokens" }
            return result
        }

        private fun parseOr(): Expr {
            var result = parseAnd()
            while (acceptWord("or")) result = Expr.Logical(Expr.Operator.OR, result, parseAnd())
            return result
        }

        private fun parseAnd(): Expr {
            var result = parseUnary()
            while (acceptWord("and")) result = Expr.Logical(Expr.Operator.AND, result, parseUnary())
            return result
        }

        private fun parseUnary(): Expr = if (acceptWord("not")) {
            Expr.Not(parseUnary())
        } else {
            parseComparison()
        }

        private fun parseComparison(): Expr {
            if (accept(Kind.LEFT)) {
                val nested = parseOr()
                require(accept(Kind.RIGHT)) { "missing ')'" }
                return nested
            }
            val left = parseOperand()
            val operator = if (peek().kind == Kind.OPERATOR) take().text else null
            if (operator == null) return left
            val right = parseOperand()
            return Expr.Compare(operator, left, right)
        }

        private fun parseOperand(): Expr {
            val token = take()
            return when (token.kind) {
                Kind.IDENTIFIER -> when (token.text.lowercase()) {
                    "true" -> Expr.Literal(Value(ValueKind.BOOLEAN, "true", "boolean"))
                    "false" -> Expr.Literal(Value(ValueKind.BOOLEAN, "false", "boolean"))
                    "nil" -> Expr.Literal(Value(ValueKind.NIL, "nil", "nil"))
                    "and", "or", "not", "require", "yield", "function", "local", "return",
                    "do", "end", "while", "for", "if", "then", "else", "elseif", "repeat",
                    "until", "goto", "break", "coroutine", "load", "loadfile", "dofile",
                    "setmetatable", "getmetatable" -> throw IllegalArgumentException("keyword in operand")
                    else -> {
                        pathEvaluator.parseSegments(token.text).getOrElse {
                            throw IllegalArgumentException(it.message ?: "invalid path")
                        }
                        Expr.Path(token.text)
                    }
                }
                Kind.NUMBER -> Expr.Literal(Value(ValueKind.NUMBER, token.text, "number"))
                Kind.STRING -> Expr.Literal(Value(ValueKind.STRING, token.text, "string"))
                else -> throw IllegalArgumentException("operand expected")
            }
        }

        private fun accept(kind: Kind): Boolean = if (peek().kind == kind) {
            index++
            true
        } else false

        private fun acceptWord(word: String): Boolean = if (
            peek().kind == Kind.IDENTIFIER && peek().text.equals(word, ignoreCase = true)
        ) {
            index++
            true
        } else false

        private fun take(): Token = peek().also { index++ }
        private fun peek(): Token = tokens.getOrElse(index) { Token(Kind.END, "") }
    }

    private inner class Lexer(private val source: String) {
        fun tokenize(): List<Token> {
            require(source.length <= maxLength) { "condition length exceeds limit" }
            val result = ArrayList<Token>()
            var cursor = 0
            while (cursor < source.length) {
                val current = source[cursor]
                if (current.isWhitespace()) {
                    cursor++
                    continue
                }
                val token = when {
                    current == '(' -> Token(Kind.LEFT, "(").also { cursor++ }
                    current == ')' -> Token(Kind.RIGHT, ")").also { cursor++ }
                    current == '\'' || current == '"' -> readString(cursor, current).also { cursor = it.second }
                        .let { Token(Kind.STRING, it.first) }
                    current.isDigit() || (current == '-' && cursor + 1 < source.length && source[cursor + 1].isDigit()) -> {
                        val start = cursor
                        cursor++
                        while (cursor < source.length && source[cursor].isDigit()) cursor++
                        if (cursor < source.length && source[cursor] == '.') {
                            cursor++
                            require(cursor < source.length && source[cursor].isDigit()) { "invalid number" }
                            while (cursor < source.length && source[cursor].isDigit()) cursor++
                        }
                        val text = source.substring(start, cursor)
                        require(text.toDoubleOrNull() != null) { "invalid number" }
                        Token(Kind.NUMBER, text)
                    }
                    current.isLetter() || current == '_' -> {
                        val start = cursor
                        cursor = readPathEnd(cursor)
                        val text = source.substring(start, cursor)
                        Token(Kind.IDENTIFIER, text)
                    }
                    current == '=' || current == '~' || current == '<' || current == '>' -> {
                        val start = cursor++
                        if (cursor < source.length && source[cursor] == '=') cursor++
                        val text = source.substring(start, cursor)
                        require(text in setOf("==", "~=", "<", "<=", ">", ">=")) { "invalid operator" }
                        Token(Kind.OPERATOR, text)
                    }
                    else -> throw IllegalArgumentException("unsupported token")
                }
                result += token
                require(result.size <= maxTokens) { "condition token limit exceeded" }
            }
            require(result.isNotEmpty()) { "empty condition" }
            return result + Token(Kind.END, "")
        }

        private fun readString(startPosition: Int, quote: Char): Pair<String, Int> {
            var cursor = startPosition + 1
            val start = cursor
            while (cursor < source.length && source[cursor] != quote) {
                require(source[cursor] != '\\' && source[cursor] != '\n' && source[cursor] != '\r') {
                    "string escapes are not supported"
                }
                cursor++
            }
            require(cursor < source.length) { "unterminated string" }
            return source.substring(start, cursor) to cursor + 1
        }

        /** Reads one complete value path; the path evaluator performs final validation. */
        private fun readPathEnd(start: Int): Int {
            var cursor = start
            fun identifierEnd(position: Int): Int {
                var end = position
                require(end < source.length && (source[end].isLetter() || source[end] == '_')) {
                    "invalid path identifier"
                }
                end++
                while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_')) end++
                return end
            }
            cursor = identifierEnd(cursor)
            while (cursor < source.length) {
                when (source[cursor]) {
                    '.' -> cursor = identifierEnd(cursor + 1)
                    '[' -> {
                        var quote: Char? = null
                        var end = cursor + 1
                        while (end < source.length) {
                            val c = source[end]
                            if (quote != null) {
                                if (c == quote) quote = null
                            } else if (c == '"') {
                                quote = c
                            } else if (c == ']') {
                                break
                            }
                            end++
                        }
                        require(end < source.length && quote == null) { "unterminated path index" }
                        cursor = end + 1
                    }
                    else -> break
                }
            }
            return cursor
        }
    }

    private fun evaluateBoolean(expr: Expr, resolver: (String) -> Result<CliCapturedValue>): Boolean = when (expr) {
        is Expr.Literal -> truthy(expr.value)
        is Expr.Path -> truthy(resolve(expr.text, resolver))
        is Expr.Not -> !evaluateBoolean(expr.child, resolver)
        is Expr.Logical -> when (expr.operator) {
            Expr.Operator.AND -> evaluateBoolean(expr.left, resolver) && evaluateBoolean(expr.right, resolver)
            Expr.Operator.OR -> evaluateBoolean(expr.left, resolver) || evaluateBoolean(expr.right, resolver)
        }
        is Expr.Compare -> compare(resolveOperand(expr.left, resolver), resolveOperand(expr.right, resolver), expr.operator)
    }

    private fun resolveOperand(expr: Expr, resolver: (String) -> Result<CliCapturedValue>): Value = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.Path -> resolve(expr.text, resolver)
        else -> throw IllegalArgumentException(CliErrorCodes.INVALID_CONDITION)
    }

    private fun resolve(path: String, resolver: (String) -> Result<CliCapturedValue>): Value {
        val result = resolver(path).getOrElse { throw it }
        if (!result.success) {
            val code = result.errorCode ?: CliErrorCodes.EVALUATION_DENIED
            throw IllegalStateException(result.errorMessage?.let { "$code: $it" } ?: code)
        }
        val type = result.type.orEmpty()
        val display = result.display.orEmpty()
        val kind = when {
            type.equals("nil", true) -> ValueKind.NIL
            type.equals("boolean", true) -> ValueKind.BOOLEAN
            type.equals("number", true) || type.equals("integer", true) ||
                type.equals("float", true) || type.equals("double", true) -> ValueKind.NUMBER
            type.equals("string", true) -> ValueKind.STRING
            else -> ValueKind.OTHER
        }
        return Value(kind, display, type)
    }

    private fun truthy(value: Value): Boolean = value.kind != ValueKind.NIL &&
        !(value.kind == ValueKind.BOOLEAN && value.text.equals("false", true))

    private fun compare(left: Value, right: Value, operator: String): Boolean {
        if (operator == "==" || operator == "~=") {
            val equal = when {
                left.kind == ValueKind.NUMBER && right.kind == ValueKind.NUMBER ->
                    left.text.toDoubleOrNull() != null && left.text.toDoubleOrNull() == right.text.toDoubleOrNull()
                left.kind != right.kind -> false
                else -> left.text == right.text
            }
            return if (operator == "==") equal else !equal
        }
        val numericLeft = left.text.toDoubleOrNull()
        val numericRight = right.text.toDoubleOrNull()
        return when {
            left.kind == ValueKind.NUMBER && right.kind == ValueKind.NUMBER &&
                numericLeft != null && numericRight != null -> compareNumbers(numericLeft, numericRight, operator)
            left.kind == ValueKind.STRING && right.kind == ValueKind.STRING ->
                compareStrings(left.text, right.text, operator)
            else -> throw IllegalArgumentException(CliErrorCodes.EVALUATION_DENIED)
        }
    }

    private fun compareNumbers(left: Double, right: Double, operator: String): Boolean = when (operator) {
        "<" -> left < right
        "<=" -> left <= right
        ">" -> left > right
        ">=" -> left >= right
        else -> throw IllegalArgumentException(CliErrorCodes.INVALID_CONDITION)
    }

    private fun compareStrings(left: String, right: String, operator: String): Boolean = when (operator) {
        "<" -> left < right
        "<=" -> left <= right
        ">" -> left > right
        ">=" -> left >= right
        else -> throw IllegalArgumentException(CliErrorCodes.INVALID_CONDITION)
    }
}

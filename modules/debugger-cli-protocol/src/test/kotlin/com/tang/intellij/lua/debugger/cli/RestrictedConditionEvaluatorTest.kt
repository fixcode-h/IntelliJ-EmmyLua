package com.tang.intellij.lua.debugger.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedConditionEvaluatorTest {
    private val evaluator = RestrictedConditionEvaluator()

    @Test
    fun `validates paths and evaluates typed comparisons`() {
        val requested = mutableListOf<String>()
        val result = evaluator.evaluate("count >= 2 and user[\"名字\"] == \"小明\"") { path ->
            requested += path
            Result.success(
                when (path) {
                    "count" -> CliCapturedValue(path, true, "number", "2")
                    "user[\"名字\"]" -> CliCapturedValue(path, true, "string", "小明")
                    else -> CliCapturedValue(path, false, errorCode = CliErrorCodes.VALUE_NOT_FOUND)
                }
            )
        }

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
        assertEquals(listOf("count", "user[\"名字\"]"), requested)
    }

    @Test
    fun `logical operators short circuit without bypassing syntax validation`() {
        var calls = 0
        val falseAnd = evaluator.evaluate("false and missing()") {
            calls++
            Result.failure(IllegalStateException(CliErrorCodes.VALUE_NOT_FOUND))
        }
        assertTrue(falseAnd.isFailure)
        assertEquals(0, calls)

        val shortCircuit = evaluator.evaluate("false and missing") {
            calls++
            Result.failure(IllegalStateException(CliErrorCodes.VALUE_NOT_FOUND))
        }
        assertTrue(shortCircuit.isSuccess)
        assertFalse(shortCircuit.getOrThrow())
        assertEquals(0, calls)
    }

    @Test
    fun `rejects arbitrary lua syntax with a stable install error`() {
        listOf(
            "x = 1",
            "require(\"os\")",
            "x()",
            "x.y()",
            "x; y",
            "yield"
        ).forEach { expression ->
            val result = evaluator.validate(expression)
            assertTrue("$expression should be rejected", result.isFailure)
            assertEquals(CliErrorCodes.INVALID_CONDITION, result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `propagates adapter error instead of converting it to false`() {
        val result = evaluator.evaluate("frame.value > 0") {
            Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        }
        assertTrue(result.isFailure)
        assertEquals(CliErrorCodes.STALE_PAUSE_REFERENCE, result.exceptionOrNull()?.message)
    }

    @Test
    fun `supports chinese strings and boolean nil truthiness`() {
        val values = mapOf(
            "text" to CliCapturedValue("text", true, "string", "中文"),
            "flag" to CliCapturedValue("flag", true, "boolean", "false"),
            "empty" to CliCapturedValue("empty", true, "nil", "nil")
        )
        fun evaluate(expression: String) = evaluator.evaluate(expression) {
            Result.success(values[it] ?: CliCapturedValue(it, false, errorCode = CliErrorCodes.VALUE_NOT_FOUND))
        }
        assertTrue(evaluate("text == \"中文\"").getOrThrow())
        assertFalse(evaluate("flag").getOrThrow())
        assertFalse(evaluate("empty").getOrThrow())
    }
}

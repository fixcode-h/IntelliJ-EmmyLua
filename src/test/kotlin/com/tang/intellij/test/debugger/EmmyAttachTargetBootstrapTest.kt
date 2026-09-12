package com.tang.intellij.test.debugger

import com.google.gson.JsonParser
import com.tang.intellij.lua.debugger.emmy.attach.AttachBootstrapStatus
import com.tang.intellij.lua.debugger.emmy.attach.AttachToolRunner
import com.tang.intellij.lua.debugger.emmy.attach.isAttachBootstrapReady
import com.tang.intellij.lua.debugger.emmy.attach.isAttachToolCompatible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

class EmmyAttachTargetBootstrapTest {
    private fun run(mode: String, timeout: Long = 2_000L) = AttachToolRunner(timeout).run(
        command = javaCommand(mode), directory = File(System.getProperty("user.dir")), authToken = "test-token"
    ) { line ->
        val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
        if (json?.get("schemaVersion")?.asInt == 1) AttachBootstrapStatus.fromJson(json) else null
    }

    @Test fun `real child process reports auth-ready`() {
        val result = run("ready")
        assertEquals(0, result.exitCode)
        assertNotNull(result.status)
        assertTrue(isAttachBootstrapReady(result.status!!, 4242))
    }

    @Test fun `nonzero exit and malformed or incomplete output are retained safely`() {
        assertEquals(7, run("nonzero").exitCode)
        assertFalse(run("bad-json").status != null)
        assertFalse(run("bad-pid").status?.let { isAttachBootstrapReady(it, 4242) } == true)
        assertFalse(run("incomplete").status?.let { isAttachBootstrapReady(it, 4242) } == true)
    }

    @Test fun `capability probe distinguishes current and legacy tools`() {
        val current = AttachToolRunner(2_000L).runCapabilities(
            javaCommand("capabilities"), File(System.getProperty("user.dir"))
        )
        assertEquals(0, current.exitCode)
        assertTrue(isAttachToolCompatible(current.capabilities))

        val legacy = AttachToolRunner(2_000L).runCapabilities(
            javaCommand("legacy"), File(System.getProperty("user.dir"))
        )
        assertEquals(0, legacy.exitCode)
        assertFalse(isAttachToolCompatible(legacy.capabilities))
        assertTrue(legacy.output.any { it == "legacy tool" })
    }

    @Test fun `unterminated tool output is retained`() {
        val result = run("unterminated")
        assertEquals(listOf("legacy tool"), result.output)
    }

    @Test fun `overlong line is bounded and sleeping child is terminated`() {
        val output = run("overlong")
        assertEquals(0, output.exitCode)
        val started = System.nanoTime()
        val timeout = runCatching { run("sleep", 150L) }.exceptionOrNull()
        assertTrue(timeout is IllegalStateException)
        assertTrue((System.nanoTime() - started) < 2_000_000_000L)
    }

    private fun javaCommand(mode: String): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classResource = EmmyAttachTargetBootstrapTest::class.java.name.replace('.', '/') + ".class"
        val resource = requireNotNull(EmmyAttachTargetBootstrapTest::class.java.classLoader.getResource(classResource))
        val testClasses = File(URI(resource.toString().removeSuffix(classResource))).absolutePath
        val classpath = testClasses + File.pathSeparator + System.getProperty("java.class.path")
        return listOf(java, "-cp", classpath, EmmyAttachTargetBootstrapTest::class.java.name, mode)
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            when (args.firstOrNull()) {
                "ready" -> println("{\"schemaVersion\":1,\"status\":\"auth-ready\",\"pid\":4242,\"injected\":true,\"listening\":true,\"authReady\":true}")
                "nonzero" -> kotlin.system.exitProcess(7)
                "bad-json" -> println("not json")
                "bad-pid" -> println("{\"schemaVersion\":1,\"status\":\"auth-ready\",\"pid\":1,\"injected\":true,\"listening\":true,\"authReady\":true}")
                "incomplete" -> println("{\"schemaVersion\":1,\"status\":\"auth-ready\",\"pid\":4242,\"injected\":true,\"listening\":true,\"authReady\":false}")
                "overlong" -> print("x".repeat(100_000))
                "sleep" -> Thread.sleep(10_000)
                "capabilities" -> println("{\"schemaVersion\":1,\"tool\":\"emmy_tool\",\"attachBootstrapStatus\":true,\"attachStatusSchemaVersion\":1,\"attachAuthTokenEnv\":\"EMMY_ATTACH_AUTH_TOKEN\"}")
                "legacy" -> println("legacy tool")
                "unterminated" -> print("legacy tool")
            }
        }
    }
}

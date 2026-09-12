package com.tang.intellij.test.debugger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ThrowableRunnable
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.util.Disposer
import com.tang.intellij.lua.debugger.cli.CliGatewayApplicationService
import com.tang.intellij.lua.debugger.cli.CliOperations
import com.tang.intellij.lua.debugger.cli.CliInstanceDescriptor
import com.tang.intellij.lua.debugger.emmy.*
import com.tang.intellij.test.LuaTestBase
import org.junit.Assert.*
import org.scalasbt.ipcsocket.Win32NamedPipeSocket
import java.io.File
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import com.intellij.openapi.vfs.VfsUtil
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Real IDE session + native fixture + production CLI Gateway smoke test. */
class EmmyNativeIdeFixtureIntegrationTest : LuaTestBase() {
    override fun runInDispatchThread(): Boolean = false

    fun testNativeFixtureIsReachableThroughRealIdeProcessAndCli() = runNativeFixtureThroughRealIdeAndCli()

    private fun runNativeFixtureThroughRealIdeAndCli() {
        val fixture = System.getenv("EMMY_IDE_FIXTURE_EXE")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("emmy.fixture.exe")?.takeIf { it.isNotBlank() }
        requireNotNull(fixture) { "set EMMY_IDE_FIXTURE_EXE or emmy.fixture.exe" }
        val template = File("EmmyLuaDebugger/tests/fixtures/cli_runtime.lua").absoluteFile
        assertTrue("fixture source missing: $template", template.isFile)
        val source = File(project.basePath!!, "cli_runtime.lua")
        // Light platform projects may be reused after another test removes
        // their physical base directory; create this fixture's source parent.
        Files.createDirectories(source.parentFile.toPath())
        Files.writeString(source.toPath(), template.readText())
        VfsUtil.markDirtyAndRefresh(false, true, true, project.baseDir)
        assertTrue("fixture source missing: $source", source.isFile)
        val pipe = "emmy-ide-test-${System.nanoTime()}"
        val token = "ide-fixture-${System.nanoTime()}"
        val child = ProcessBuilder(fixture, "--pipe", pipe, "--source", source.path,
            "--source-hash", sha256(source)).apply {
            environment()["EMMY_IDE_FIXTURE_TOKEN"] = token
            redirectErrorStream(true)
        }.start()
        val fixtureOutput = FixtureOutput(child)
        var fixtureProcess: FixtureProcess? = null
        var session: com.intellij.xdebugger.XDebugSession? = null
        var runContentDescriptor: com.intellij.execution.ui.RunContentDescriptor? = null
        var testFailure: Throwable? = null
        try {
            assertNotNull("Native fixture did not become ready: ${fixtureOutput.text()}", waitFor(5_000) {
                check(child.isAlive) { "Native fixture exited: ${fixtureOutput.text()}" }
                true.takeIf { fixtureOutput.text().contains("\"ready\":true") }
            })
            EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
                session = XDebuggerManager.getInstance(project).startSessionAndShowTab(
                    "Emmy native fixture", object : XDebugProcessStarter() {
                        override fun start(session: com.intellij.xdebugger.XDebugSession): XDebugProcess =
                            FixtureProcess(session, pipe, token).also { fixtureProcess = it }
                    }, environment())
                runContentDescriptor = session?.runContentDescriptor
            })
            val app = CliGatewayApplicationService.getInstance()
            val target = waitFor(30_000) {
                fixtureProcess?.let { process -> app.registry().adapters().firstOrNull { it.targetId == process.debugTargetId } }
            }
            assertNotNull("Emmy target was not registered", target)
            val adapter = target!!
            val vm = waitFor(30_000) { adapter.listVms().firstOrNull() }
            assertNotNull("native fixture VM was not registered", vm)
            assertNotNull("CLI endpoint was not published", app.endpoint())
            val descriptor = app.endpoint()!!
            val clientId = "fixture-test"
            app.grant(adapter.targetId, clientId)
            val vmInfo = vm!!
            GatewaySocket(descriptor, clientId).use { client ->
                val lease = client.request(CliOperations.LEASE_ACQUIRE, adapter.targetId,
                    JsonObject().apply { addProperty("ttlMillis", 120_000) }, null)
                assertTrue(lease.get("ok").asBoolean)
                val leaseId = lease.get("data").asJsonObject.get("leaseId").asString
                val sourceIdentity = JsonObject().apply {
                    addProperty("uri", source.toURI().toString())
                    addProperty("canonicalPath", source.canonicalPath)
                    addProperty("sourceHash", sha256(source))
                    addProperty("sourceEpoch", 1)
                    addProperty("verified", true)
                }
                val breakpoint = client.request(CliOperations.BREAKPOINT_ADD, adapter.targetId,
                    JsonObject().apply {
                        add("breakpoints", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("breakpointId", "fixture-line-3")
                                addProperty("vmId", vmInfo.vmId)
                                add("sourceIdentity", sourceIdentity)
                                addProperty("line", 3)
                            })
                        })
                    }, leaseId)
                assertTrue(breakpoint.toString(), breakpoint.get("ok").asBoolean)
                val pause = waitFor(30_000) { adapter.currentPause(vmInfo.vmId) }
                assertNotNull("native fixture did not pause at line 3 breakpoint", pause)
                val frame = pause!!.frames.first()
                assertEquals(3, frame.line)
                assertNotNull("target disappeared before eval: ${fixtureProcess!!.debugTargetSummary()}",
                    app.registry().adapter(adapter.targetId))
                val eval = client.request(CliOperations.EVALUATE, adapter.targetId, JsonObject().apply {
                    addProperty("vmId", vmInfo.vmId)
                    addProperty("pauseId", pause.reference.pauseId)
                    addProperty("frameId", frame.frameId)
                    addProperty("expression", "value.answer")
                }, leaseId)
                assertTrue(eval.toString(), eval.get("ok").asBoolean)
                assertEquals("42", eval.get("data").asJsonObject.get("display").asString)
                val specialKeyEval = client.request(CliOperations.EVALUATE, adapter.targetId, JsonObject().apply {
                    addProperty("vmId", vmInfo.vmId)
                    addProperty("pauseId", pause.reference.pauseId)
                    addProperty("frameId", frame.frameId)
                    addProperty("expression", "value[\"a.b\"].nested")
                }, leaseId)
                assertTrue(specialKeyEval.toString(), specialKeyEval.get("ok").asBoolean)
                assertEquals("ok", specialKeyEval.get("data").asJsonObject.get("display").asString)
                val tableEval = client.request(CliOperations.EVALUATE, adapter.targetId, JsonObject().apply {
                    addProperty("vmId", vmInfo.vmId)
                    addProperty("pauseId", pause.reference.pauseId)
                    addProperty("frameId", frame.frameId)
                    addProperty("expression", "value")
                    addProperty("maxDepth", 3)
                }, leaseId)
                assertTrue(tableEval.toString(), tableEval.get("ok").asBoolean)
                val tableChildren = tableEval.getAsJsonObject("data").getAsJsonArray("children")
                assertEquals("42", tableChildren.first { it.asJsonObject.get("name").asString == "answer" }
                    .asJsonObject.get("display").asString)
                assertEquals("ok", tableChildren.first { it.asJsonObject.get("name").asString == "a.b" }
                    .asJsonObject.getAsJsonArray("children").single().asJsonObject.get("display").asString)

                val scopes = client.request(CliOperations.SCOPES, adapter.targetId, JsonObject().apply {
                    addProperty("vmId", vmInfo.vmId)
                    addProperty("pauseId", pause.reference.pauseId)
                    addProperty("frameId", frame.frameId)
                }, null)
                assertTrue(scopes.toString(), scopes.get("ok").asBoolean)
                val scopeReference = scopes.get("data").asJsonObject.get("scopes").asJsonArray
                    .first { it.asJsonObject.get("name").asString == "locals" }
                    .asJsonObject.get("variablesReference").asString
                val variables = client.request(CliOperations.VARIABLES, adapter.targetId, JsonObject().apply {
                    addProperty("vmId", vmInfo.vmId)
                    addProperty("pauseId", pause.reference.pauseId)
                    addProperty("frameId", frame.frameId)
                    addProperty("variablesReference", scopeReference)
                }, null)
                assertTrue(variables.toString(), variables.get("ok").asBoolean)
                assertEquals("value", variables.get("data").asJsonObject.get("variables")
                    .asJsonArray.first().asJsonObject.get("name").asString)
                val probeId = "fixture-probe"
                val probe = client.request(CliOperations.PROBE_RUN, adapter.targetId, JsonObject().apply {
                    addProperty("probeId", probeId)
                    addProperty("vmId", vmInfo.vmId)
                    addProperty("line", 3)
                    addProperty("condition", "value.answer == 42")
                    add("captures", com.google.gson.JsonArray().apply {
                        add("value.answer")
                        add("value[\"a.b\"].nested")
                    })
                    addProperty("hitLimit", 1)
                    addProperty("autoContinue", true)
                    add("sourceIdentity", sourceIdentity)
                }, leaseId)
                assertTrue(probe.toString(), probe.get("ok").asBoolean)
                val remove = client.request(CliOperations.BREAKPOINT_REMOVE, adapter.targetId, JsonObject().apply {
                    add("breakpointIds", com.google.gson.JsonArray().apply { add("fixture-line-3") })
                }, leaseId)
                assertTrue(remove.toString(), remove.get("ok").asBoolean)
                val continueResponse = client.request(CliOperations.CONTINUE, adapter.targetId, JsonObject().apply {
                    addProperty("vmId", vmInfo.vmId)
                    addProperty("pauseId", pause.reference.pauseId)
                }, leaseId)
                assertTrue(continueResponse.toString(), continueResponse.get("ok").asBoolean)
                var eventCursor = 0L
                val hit = waitFor(30_000) {
                    val batch = client.waitEvents(adapter.targetId, eventCursor, 1_000)
                    eventCursor = batch.first
                    batch.second.firstOrNull {
                        it.get("type")?.asString == "probe.hit" &&
                            it.get("payload")?.asJsonObject?.get("probeId")?.asString == probeId
                    }
                }
                assertNotNull("probe.hit event was not published", hit)
                val hitEvent = hit!!
                assertEquals("42", hitEvent.get("payload").asJsonObject.get("values").asJsonArray[0]
                    .asJsonObject.get("display").asString)
                assertEquals("ok", hitEvent.get("payload").asJsonObject.get("values").asJsonArray[1]
                    .asJsonObject.get("display").asString)
                assertTrue(hitEvent.get("payload").asJsonObject.get("autoContinued").asBoolean)
                assertNotNull("Probe did not complete", waitFor(5_000) {
                    val status = client.request(CliOperations.PROBE_STATUS, adapter.targetId,
                        JsonObject().apply { addProperty("probeId", probeId) }, null)
                    assertTrue(status.toString(), status.get("ok").asBoolean)
                    true.takeIf { status.getAsJsonObject("data").get("state").asString == "COMPLETED" }
                })
                assertNotNull("Probe breakpoint was not automatically removed", waitFor(5_000) {
                    val listed = client.request(CliOperations.BREAKPOINT_LIST, adapter.targetId, JsonObject(), null)
                    assertTrue(listed.toString(), listed.get("ok").asBoolean)
                    true.takeIf { listed.getAsJsonObject("data").getAsJsonArray("breakpoints").none {
                        it.asJsonObject.get("breakpointId").asString == "probe:$probeId"
                    } }
                })
                val staleEval = client.request(CliOperations.EVALUATE, adapter.targetId, JsonObject().apply {
                    addProperty("vmId", vmInfo.vmId)
                    addProperty("pauseId", pause.reference.pauseId)
                    addProperty("frameId", frame.frameId)
                    addProperty("expression", "value.answer")
                }, leaseId)
                assertFalse(staleEval.toString(), staleEval.get("ok").asBoolean)
                assertEquals("STALE_PAUSE_REFERENCE", staleEval.get("error").asJsonObject.get("code").asString)

                command(child, "reset")
                assertNotNull("IDE did not receive VM context reset", waitFor(5_000) {
                    adapter.listVms().firstOrNull {
                        it.vmId == vmInfo.vmId && (it.sourceEpoch ?: 0) > (vmInfo.sourceEpoch ?: 0) &&
                            (it.contextGeneration ?: 0) > (vmInfo.contextGeneration ?: 0)
                    }
                })
                val staleVariables = client.request(CliOperations.VARIABLES, adapter.targetId, JsonObject().apply {
                    addProperty("vmId", vmInfo.vmId)
                    addProperty("pauseId", pause.reference.pauseId)
                    addProperty("frameId", frame.frameId)
                    addProperty("variablesReference", scopeReference)
                }, null)
                assertFalse(staleVariables.toString(), staleVariables.get("ok").asBoolean)
                assertEquals("STALE_PAUSE_REFERENCE", staleVariables.getAsJsonObject("error").get("code").asString)

                command(child, "close-vm")
                val closeStates = mutableListOf<String>()
                assertNotNull("IDE/CLI did not receive VM closing and closed events", waitFor(5_000) {
                    val batch = client.waitEvents(adapter.targetId, eventCursor, 1_000)
                    eventCursor = batch.first
                    batch.second.filter { it.get("type")?.asString == "vm.lifecycle" &&
                        it.get("vmId")?.asString == vmInfo.vmId }.forEach {
                        closeStates += it.getAsJsonObject("payload").get("state").asString
                    }
                    true.takeIf { "CLOSED" in closeStates }
                })
                assertTrue(closeStates.toString(), closeStates.indexOf("CLOSING") >= 0 &&
                    closeStates.indexOf("CLOSING") < closeStates.indexOf("CLOSED"))
                assertTrue("Agent exited when only VM closed", child.isAlive)
                val targetStatus = client.request(CliOperations.TARGET_STATUS, adapter.targetId, JsonObject(), null)
                assertTrue(targetStatus.toString(), targetStatus.get("ok").asBoolean)
                assertTrue(targetStatus.getAsJsonObject("data").get("agentReady").asBoolean)
                assertFalse(targetStatus.getAsJsonObject("data").get("vmReady").asBoolean)
                assertNull(adapter.currentPause(vmInfo.vmId))
            }
        } catch (failure: Throwable) {
            failure.addSuppressed(AssertionError("Native fixture alive=${child.isAlive}; output=${fixtureOutput.text()}"))
            testFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            runCatching {
                child.outputStream.close()
                val exited = child.waitFor(5, TimeUnit.SECONDS)
                if (!exited) {
                    child.destroyForcibly()
                    check(child.waitFor(5, TimeUnit.SECONDS)) { "fixture could not be stopped" }
                }
                check(exited && child.exitValue() == 0) { "fixture did not exit gracefully: ${fixtureOutput.text()}" }
                fixtureOutput.join()
                check(fixtureOutput.text().contains("\"closed\":true")) { "fixture close was not acknowledged" }
            }.exceptionOrNull()?.let(cleanupFailures::add)
            session?.let { activeSession -> runCatching {
                EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
                    activeSession.stop()
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                })
                EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
                    runContentDescriptor?.let { descriptor ->
                        RunContentManager.getInstance(project).removeRunContent(
                            DefaultDebugExecutor.getDebugExecutorInstance(), descriptor)
                        if (!Disposer.isDisposed(descriptor)) Disposer.dispose(descriptor)
                    }
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                })
            }.exceptionOrNull()?.let(cleanupFailures::add) }
            if (cleanupFailures.isNotEmpty()) {
                val primary = testFailure ?: cleanupFailures.removeAt(0)
                cleanupFailures.forEach(primary::addSuppressed)
                if (testFailure == null) throw primary
            }
        }
    }

    private fun command(child: Process, command: String) {
        child.outputStream.write("$command\n".toByteArray(StandardCharsets.UTF_8))
        child.outputStream.flush()
    }

    private class FixtureOutput(child: Process) {
        private val output = StringBuilder()
        private val reader = Thread({
            child.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line -> synchronized(output) {
                    output.append(line.take(4096)).append('\n')
                    if (output.length > 16 * 1024) output.delete(0, output.length - 16 * 1024)
                } }
            }
        }, "Emmy fixture output").apply { isDaemon = true; start() }

        fun text(): String = synchronized(output) { output.toString() }
        fun join() = reader.join(2_000)
    }

    private class GatewaySocket(private val descriptor: CliInstanceDescriptor, private val clientId: String) : AutoCloseable {
        private val socket = if (descriptor.endpoint.startsWith("npipe://"))
            Win32NamedPipeSocket("\\\\.\\pipe\\${descriptor.endpoint.removePrefix("npipe://")}")
        else {
            val uri = java.net.URI(descriptor.endpoint)
            Socket(uri.host, uri.port)
        }
        private val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        private val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)

        init {
            writer.write("{\"token\":\"${File(descriptor.tokenFile).readText().trim()}\"}\n")
            writer.flush()
            assertTrue(reader.readLine().contains("authenticated"))
        }

        fun request(operation: String, targetId: String, arguments: JsonObject, leaseId: String?): JsonObject {
            val request = JsonObject().apply {
                addProperty("requestId", "fixture-${System.nanoTime()}")
                addProperty("operation", operation)
                addProperty("targetId", targetId)
                addProperty("clientId", clientId)
                leaseId?.let { addProperty("leaseId", it) }
                add("arguments", arguments)
            }
            writer.write(request.toString())
            writer.newLine()
            writer.flush()
            return JsonParser.parseString(reader.readLine()).asJsonObject
        }

        fun waitEvents(targetId: String, cursor: Long, limit: Int): Pair<Long, List<JsonObject>> {
            val request = JsonObject().apply {
                addProperty("requestId", "fixture-${System.nanoTime()}")
                addProperty("operation", CliOperations.WAIT)
                addProperty("targetId", targetId)
                addProperty("clientId", clientId)
                add("arguments", JsonObject().apply {
                    addProperty("cursor", cursor)
                    addProperty("limit", limit)
                    addProperty("timeoutMillis", 1_000)
                })
            }
            writer.write(request.toString())
            writer.newLine()
            writer.flush()
            var nextCursor = cursor
            val events = mutableListOf<JsonObject>()
            while (true) {
                val response = JsonParser.parseString(reader.readLine()).asJsonObject
                if (!response.get("ok").asBoolean) {
                    assertEquals(response.toString(), "TIMEOUT", response.getAsJsonObject("error").get("code").asString)
                    return nextCursor to events
                }
                response.get("cursor")?.takeUnless { it.isJsonNull }?.asLong?.let { nextCursor = it }
                response.get("data")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { data ->
                    if (data.has("type")) events += data
                }
                if (response.get("done")?.asBoolean == true) return nextCursor to events
            }
        }

        override fun close() = socket.close()
    }

    private fun environment(): ExecutionEnvironment {
        val configType = EmmyDebugConfigurationType()
        val profile = EmmyDebugConfiguration(project, EmmyDebuggerConfigurationFactory(configType)).apply {
            name = "Emmy native fixture"
            type = EmmyDebugTransportType.PIPE_CLIENT
        }
        return ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), profile).build()
    }

    private fun <T> waitFor(timeoutMillis: Long, value: () -> T?): T? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            value()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(file.toPath())).joinToString("") { "%02x".format(it) }

    private class FixtureProcess(
        session: com.intellij.xdebugger.XDebugSession,
        private val pipe: String,
        private val token: String
    ) : EmmyDebugProcessBase(session) {
        override fun createTargetBootstrap(): EmmyTargetBootstrap = object : EmmyTargetBootstrap {
            override val authToken: String = token
            override fun prepareTransports(): List<Transporter> = listOf(PipelineClientTransporter(pipe))
        }
    }
}

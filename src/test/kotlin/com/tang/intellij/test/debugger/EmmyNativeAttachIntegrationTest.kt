package com.tang.intellij.test.debugger

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ThrowableRunnable
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebugSession
import com.tang.intellij.lua.debugger.cli.CliGatewayApplicationService
import com.tang.intellij.lua.debugger.cli.CliInstanceDescriptor
import com.tang.intellij.lua.debugger.cli.CliOperations
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachDebugConfiguration
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachDebugProcess
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachConfigurationType
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachDebuggerConfigurationFactory
import com.tang.intellij.lua.debugger.emmy.EmmyWinArch
import com.tang.intellij.test.LuaTestBase
import org.junit.Assert.*
import org.scalasbt.ipcsocket.Win32NamedPipeSocket
import java.io.File
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Real emmy_tool attach + Lua VM + production IDEA/CLI contract test. */
class EmmyNativeAttachIntegrationTest : LuaTestBase() {
    override fun runInDispatchThread(): Boolean = false

    fun testRealAttachFixtureThroughProductionAttachAndCli() {
        val fixture = System.getenv("EMMY_ATTACH_FIXTURE_EXE")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("emmy.attach.fixture.exe")?.takeIf { it.isNotBlank() }
        requireNotNull(fixture) { "set EMMY_ATTACH_FIXTURE_EXE or emmy.attach.fixture.exe" }
        val fixtureFile = File(fixture).absoluteFile
        assertTrue("attach fixture missing: $fixtureFile", fixtureFile.isFile)
        assertTrue("fixture must ship beside the shared Lua DLL",
            File(fixtureFile.parentFile, "lua54.dll").isFile)

        val template = File("EmmyLuaDebugger/tests/fixtures/attach_runtime.lua").absoluteFile
        assertTrue("fixture source missing: $template", template.isFile)
        val source = File(project.basePath!!, "emmy_attach_runtime.lua")
        Files.createDirectories(source.parentFile.toPath())
        Files.writeString(source.toPath(), template.readText())
        VfsUtil.markDirtyAndRefresh(false, true, true, project.baseDir)

        val child = ProcessBuilder(fixtureFile.absolutePath, "--source", source.canonicalPath)
            .directory(fixtureFile.parentFile)
            .redirectErrorStream(true)
            .start()
        val fixtureOutput = FixtureOutput(child)
        var session: XDebugSession? = null
        var descriptor: com.intellij.execution.ui.RunContentDescriptor? = null
        var debugProcess: EmmyAttachDebugProcess? = null
        var primaryFailure: Throwable? = null
        try {
            val pid = waitFor(15_000) {
                check(child.isAlive) { "fixture exited before ready: ${fixtureOutput.text()}" }
                fixtureOutput.readyPid()
            }
            assertNotNull("fixture did not become ready: ${fixtureOutput.text()}", pid)
            val attachPid = pid!!

            val profile = EmmyAttachDebugConfiguration(
                project, EmmyAttachDebuggerConfigurationFactory(EmmyAttachConfigurationType())
            ).apply {
                name = "Emmy real attach fixture"
                this.pid = attachPid
                processName = fixtureFile.name
                winArch = EmmyWinArch.X64
                captureLog = false
                autoAttachSingleProcess = false
                filterUEProcesses = false
                // Keep the real attach trace in the test console. VM identity,
                // breakpoint ACK, and pause rejection diagnostics are DEBUG.
                logLevel = com.tang.intellij.lua.debugger.DebugLogLevel.DEBUG
            }
            val environment = ExecutionEnvironmentBuilder.create(
                project, DefaultDebugExecutor.getDebugExecutorInstance(), profile
            ).build()
            EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
                session = XDebuggerManager.getInstance(project).startSessionAndShowTab(
                    "Emmy real attach fixture", object : XDebugProcessStarter() {
                        override fun start(current: XDebugSession): XDebugProcess =
                            EmmyAttachDebugProcess(current).also { debugProcess = it }
                    }, environment)
                descriptor = session?.runContentDescriptor
            })

            val process = checkNotNull(debugProcess) { "production attach process was not created" }
            val app = CliGatewayApplicationService.getInstance()
            val adapter = waitFor(30_000) { app.registry().adapter(process.debugTargetId) }
            assertNotNull("production attach target was not registered", adapter)
            val target = adapter!!
            val vm = waitFor(30_000) {
                target.listVms().firstOrNull { it.state in setOf("READY", "RUNNING", "PAUSED") }
            }
            assertNotNull("fallback VM snapshot was not received", vm)
            val initialVm = vm!!
            val endpoint = checkNotNull(app.endpoint()) { "CLI endpoint was not published" }
            val clientId = "attach-fixture-test"
            app.grant(target.targetId, clientId)

            GatewaySocket(endpoint, clientId).use { client ->
                val lease = client.request(CliOperations.LEASE_ACQUIRE, target.targetId,
                    JsonObject().apply { addProperty("ttlMillis", 120_000) }, null)
                assertOk(lease)
                val leaseId = lease.getAsJsonObject("data").get("leaseId").asString
                // This host has no SourceRegistry entry. Path matching is still
                // strict, while verified/hash/epoch matching is intentionally absent.
                val sourceIdentity = JsonObject().apply {
                    addProperty("uri", source.toURI().toString())
                    addProperty("canonicalPath", source.canonicalPath)
                    addProperty("verified", false)
                }
                val breakpoint = client.request(CliOperations.BREAKPOINT_ADD, target.targetId,
                    JsonObject().apply {
                        add("breakpoints", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("breakpointId", "attach-line-3")
                                addProperty("vmId", initialVm.vmId)
                                add("sourceIdentity", sourceIdentity)
                                addProperty("line", 3)
                            })
                        })
                    }, leaseId)
                assertOk(breakpoint)

                val pause = waitFor(30_000) { target.currentPause(initialVm.vmId) }
                assertNotNull("real attach fixture did not hit line 3", pause)
                val firstPause = pause!!
                val frame = firstPause.frames.firstOrNull { it.line == 3 }
                    ?: throw AssertionError("line 3 frame missing: ${firstPause.frames}")
                val pauseArgs = JsonObject().apply {
                    addProperty("vmId", initialVm.vmId)
                    addProperty("pauseId", firstPause.reference.pauseId)
                    addProperty("frameId", frame.frameId)
                }

                val answer = client.request(CliOperations.EVALUATE, target.targetId,
                    copyJson(pauseArgs).apply { addProperty("expression", "value.answer") }, leaseId)
                assertOk(answer)
                assertEquals("42", answer.getAsJsonObject("data").get("display").asString)
                val special = client.request(CliOperations.EVALUATE, target.targetId,
                    copyJson(pauseArgs).apply {
                        addProperty("expression", "value[\"a.b\"].nested")
                    }, leaseId)
                assertOk(special)
                assertEquals("ok", special.getAsJsonObject("data").get("display").asString)

                val table = client.request(CliOperations.EVALUATE, target.targetId,
                    copyJson(pauseArgs).apply {
                        addProperty("expression", "value")
                        addProperty("maxDepth", 3)
                    }, leaseId)
                assertOk(table)
                val children = table.getAsJsonObject("data").getAsJsonArray("children")
                assertEquals("42", children.first { it.asJsonObject.get("name").asString == "answer" }
                    .asJsonObject.get("display").asString)
                assertEquals("ok", children.first { it.asJsonObject.get("name").asString == "a.b" }
                    .asJsonObject.getAsJsonArray("children").single().asJsonObject.get("display").asString)

                val scopes = client.request(CliOperations.SCOPES, target.targetId, pauseArgs, null)
                assertOk(scopes)
                val localsRef = scopes.getAsJsonObject("data").getAsJsonArray("scopes")
                    .first { it.asJsonObject.get("name").asString == "locals" }
                    .asJsonObject.get("variablesReference").asString
                val variables = client.request(CliOperations.VARIABLES, target.targetId,
                    copyJson(pauseArgs).apply { addProperty("variablesReference", localsRef) }, null)
                assertOk(variables)
                assertEquals("value", variables.getAsJsonObject("data").getAsJsonArray("variables")
                    .first().asJsonObject.get("name").asString)

                val probeId = "attach-probe"
                val probe = client.request(CliOperations.PROBE_RUN, target.targetId,
                    JsonObject().apply {
                        addProperty("probeId", probeId)
                        addProperty("vmId", initialVm.vmId)
                        add("sourceIdentity", sourceIdentity)
                        addProperty("line", 3)
                        addProperty("condition", "value.answer == 42")
                        add("captures", JsonArray().apply {
                            add("value.answer")
                            add("value[\"a.b\"].nested")
                        })
                        addProperty("hitLimit", 1)
                        addProperty("autoContinue", true)
                    }, leaseId)
                assertOk(probe)
                assertOk(client.request(CliOperations.BREAKPOINT_REMOVE, target.targetId,
                    JsonObject().apply { add("breakpointIds", JsonArray().apply { add("attach-line-3") }) }, leaseId))
                assertOk(client.request(CliOperations.CONTINUE, target.targetId,
                    JsonObject().apply {
                        addProperty("vmId", initialVm.vmId)
                        addProperty("pauseId", firstPause.reference.pauseId)
                    }, leaseId))

                var cursor = 0L
                val hit = waitFor(30_000) {
                    val page = client.waitEvents(target.targetId, cursor, 1_000)
                    cursor = page.first
                    page.second.firstOrNull {
                        it.get("type")?.asString == "probe.hit" &&
                            it.getAsJsonObject("payload")?.get("probeId")?.asString == probeId
                    }
                }
                assertNotNull("probe.hit was not published", hit)
                val hitPayload = hit!!.getAsJsonObject("payload")
                assertEquals("42", hitPayload.getAsJsonArray("values")[0].asJsonObject.get("display").asString)
                assertEquals("ok", hitPayload.getAsJsonArray("values")[1].asJsonObject.get("display").asString)
                assertTrue(hitPayload.get("autoContinued").asBoolean)
                assertNotNull("probe did not complete", waitFor(5_000) {
                    val status = client.request(CliOperations.PROBE_STATUS, target.targetId,
                        JsonObject().apply { addProperty("probeId", probeId) }, null)
                    assertOk(status)
                    true.takeIf { status.getAsJsonObject("data").get("state").asString == "COMPLETED" }
                })
                assertNotNull("probe breakpoint was not cleaned up", waitFor(5_000) {
                    val listed = client.request(CliOperations.BREAKPOINT_LIST, target.targetId, JsonObject(), null)
                    assertOk(listed)
                    true.takeIf { listed.getAsJsonObject("data").getAsJsonArray("breakpoints").none {
                        it.asJsonObject.get("breakpointId").asString == "probe:$probeId"
                    } }
                })

                val staleEval = client.request(CliOperations.EVALUATE, target.targetId,
                    copyJson(pauseArgs).apply { addProperty("expression", "value.answer") }, leaseId)
                assertFalse(staleEval.toString(), staleEval.get("ok").asBoolean)
                assertEquals("STALE_PAUSE_REFERENCE", staleEval.getAsJsonObject("error").get("code").asString)

                command(child, "reset")
                val replacementVm = waitFor(15_000) {
                    target.listVms().firstOrNull {
                        it.vmId != initialVm.vmId && it.state in setOf("READY", "RUNNING", "PAUSED")
                    }
                }
                assertNotNull("reset did not create a new fallback VM: ${fixtureOutput.text()}", replacementVm)
                val staleVariables = client.request(CliOperations.VARIABLES, target.targetId,
                    copyJson(pauseArgs).apply { addProperty("variablesReference", localsRef) }, null)
                assertFalse(staleVariables.toString(), staleVariables.get("ok").asBoolean)
                assertTrue(staleVariables.getAsJsonObject("error").get("code").asString in
                    setOf("STALE_PAUSE_REFERENCE", "VM_NOT_FOUND", "CONTEXT_RESET"))

                val replacementVmId = replacementVm!!.vmId
                command(child, "close-vm")
                val closeStates = mutableListOf<String>()
                assertNotNull("VM close lifecycle was not published", waitFor(10_000) {
                    val page = client.waitEvents(target.targetId, cursor, 1_000)
                    cursor = page.first
                    page.second.filter {
                        it.get("type")?.asString == "vm.lifecycle" &&
                            it.get("vmId")?.asString == replacementVmId
                    }.forEach { closeStates += it.getAsJsonObject("payload").get("state").asString }
                    true.takeIf { "CLOSED" in closeStates }
                })
                assertTrue(closeStates.toString(), closeStates.indexOf("CLOSING") >= 0 &&
                    closeStates.indexOf("CLOSING") < closeStates.indexOf("CLOSED"))
                assertTrue("closing only the VM must keep Agent alive", child.isAlive)
                val status = client.request(CliOperations.TARGET_STATUS, target.targetId, JsonObject(), null)
                assertOk(status)
                assertTrue(status.getAsJsonObject("data").get("agentReady").asBoolean)
                assertFalse(status.getAsJsonObject("data").get("vmReady").asBoolean)
            }
            command(child, "stop")
        } catch (failure: Throwable) {
            failure.addSuppressed(AssertionError("fixture alive=${child.isAlive}; output=${fixtureOutput.text()}"))
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            runCatching {
                if (child.isAlive) {
                    runCatching { command(child, "stop") }
                    child.outputStream.close()
                }
                val exited = child.waitFor(8, TimeUnit.SECONDS)
                if (!exited) {
                    child.destroyForcibly()
                    check(child.waitFor(5, TimeUnit.SECONDS)) { "fixture could not be stopped" }
                }
                check(child.exitValue() == 0) { "fixture exit=${child.exitValue()}: ${fixtureOutput.text()}" }
                fixtureOutput.join()
                check(fixtureOutput.text().contains("\"closed\":true")) {
                    "fixture close was not acknowledged: ${fixtureOutput.text()}"
                }
            }.exceptionOrNull()?.let(cleanupFailures::add)
            session?.let { activeSession -> runCatching {
                EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
                    activeSession.stop()
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                })
                EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
                    descriptor?.let { runDescriptor ->
                        RunContentManager.getInstance(project).removeRunContent(
                            DefaultDebugExecutor.getDebugExecutorInstance(), runDescriptor)
                        if (!Disposer.isDisposed(runDescriptor)) Disposer.dispose(runDescriptor)
                    }
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                })
            }.exceptionOrNull()?.let(cleanupFailures::add) }
            if (cleanupFailures.isNotEmpty()) {
                val primary = primaryFailure ?: cleanupFailures.removeAt(0)
                cleanupFailures.forEach(primary::addSuppressed)
                if (primaryFailure == null) throw primary
            }
        }
    }

    /**
     * The production attach path injects the agent once and reconfigures the
     * agent that already lives inside the target process on every later attach
     * ("Reconfigure existing Emmy Agent"). A stop/attach cycle used to leave the
     * second session without a usable VM, so the debug window never appeared.
     * This drives the real emmy_tool reconfigure path end to end.
     */
    fun testReattachSameProcessAfterSessionStop() {
        val fixture = System.getenv("EMMY_ATTACH_FIXTURE_EXE")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("emmy.attach.fixture.exe")?.takeIf { it.isNotBlank() }
        requireNotNull(fixture) { "set EMMY_ATTACH_FIXTURE_EXE or emmy.attach.fixture.exe" }
        val fixtureFile = File(fixture).absoluteFile
        assertTrue("attach fixture missing: $fixtureFile", fixtureFile.isFile)
        assertTrue("fixture must ship beside the shared Lua DLL",
            File(fixtureFile.parentFile, "lua54.dll").isFile)
        val template = File("EmmyLuaDebugger/tests/fixtures/attach_runtime.lua").absoluteFile
        assertTrue("fixture source missing: $template", template.isFile)
        val source = File(project.basePath!!, "emmy_attach_runtime.lua")
        Files.createDirectories(source.parentFile.toPath())
        Files.writeString(source.toPath(), template.readText())
        VfsUtil.markDirtyAndRefresh(false, true, true, project.baseDir)

        val child = ProcessBuilder(fixtureFile.absolutePath, "--source", source.canonicalPath)
            .directory(fixtureFile.parentFile)
            .redirectErrorStream(true)
            .start()
        val fixtureOutput = FixtureOutput(child)
        val attached = mutableListOf<AttachedSession>()
        var primaryFailure: Throwable? = null
        try {
            val pid = waitFor(15_000) {
                check(child.isAlive) { "fixture exited before ready: ${fixtureOutput.text()}" }
                fixtureOutput.readyPid()
            }
            assertNotNull("fixture did not become ready: ${fixtureOutput.text()}", pid)
            val app = CliGatewayApplicationService.getInstance()

            val first = startAttachSession(pid!!, "Emmy reattach fixture #1", fixtureFile, attached)
            val firstAdapter = waitFor(30_000) { app.registry().adapter(first.process.debugTargetId) }
            assertNotNull("first attach target was not registered", firstAdapter)
            assertNotNull("first attach produced no usable VM: ${fixtureOutput.text()}",
                waitFor(30_000) {
                    firstAdapter!!.listVms().firstOrNull { it.state in setOf("READY", "RUNNING", "PAUSED") }
                })

            // Simulate the user's "disable / stop debug" before attaching again.
            EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
                first.session.stop()
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            })
            assertTrue("first session did not stop", waitFor(15_000) { true.takeIf { first.session.isStopped } } ?: false)

            // The second attach reuses the injected agent: emmy_tool reconfigures
            // it instead of loading emmy_hook.dll again.
            val second = startAttachSession(pid, "Emmy reattach fixture #2", fixtureFile, attached)
            val secondAdapter = waitFor(30_000) { app.registry().adapter(second.process.debugTargetId) }
            assertNotNull("reattach target was not registered: ${fixtureOutput.text()}", secondAdapter)
            val secondVm = waitFor(30_000) {
                secondAdapter!!.listVms().firstOrNull { it.state in setOf("READY", "RUNNING", "PAUSED") }
            }
            assertNotNull(
                "reattach produced no usable VM (agent reconfigure failed): ${fixtureOutput.text()}",
                secondVm
            )
        } catch (failure: Throwable) {
            failure.addSuppressed(AssertionError("fixture alive=${child.isAlive}; output=${fixtureOutput.text()}"))
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            runCatching {
                if (child.isAlive) {
                    runCatching { command(child, "stop") }
                    child.outputStream.close()
                }
                val exited = child.waitFor(8, TimeUnit.SECONDS)
                if (!exited) {
                    child.destroyForcibly()
                    check(child.waitFor(5, TimeUnit.SECONDS)) { "fixture could not be stopped" }
                }
                check(child.exitValue() == 0) { "fixture exit=${child.exitValue()}: ${fixtureOutput.text()}" }
                fixtureOutput.join()
            }.exceptionOrNull()?.let(cleanupFailures::add)
            runCatching { disposeAttached(attached) }.exceptionOrNull()?.let(cleanupFailures::add)
            if (cleanupFailures.isNotEmpty()) {
                val primary = primaryFailure ?: cleanupFailures.removeAt(0)
                cleanupFailures.forEach(primary::addSuppressed)
                if (primaryFailure == null) throw primary
            }
        }
    }

    private class AttachedSession(
        val session: XDebugSession,
        val descriptor: com.intellij.execution.ui.RunContentDescriptor?,
        val process: EmmyAttachDebugProcess
    )

    private fun startAttachSession(
        pid: Int,
        name: String,
        fixtureFile: File,
        attached: MutableList<AttachedSession>
    ): AttachedSession {
        val profile = EmmyAttachDebugConfiguration(
            project, EmmyAttachDebuggerConfigurationFactory(EmmyAttachConfigurationType())
        ).apply {
            this.name = name
            this.pid = pid
            processName = fixtureFile.name
            winArch = EmmyWinArch.X64
            captureLog = false
            autoAttachSingleProcess = false
            filterUEProcesses = false
            logLevel = com.tang.intellij.lua.debugger.DebugLogLevel.DEBUG
        }
        val environment = ExecutionEnvironmentBuilder.create(
            project, DefaultDebugExecutor.getDebugExecutorInstance(), profile
        ).build()
        var session: XDebugSession? = null
        var descriptor: com.intellij.execution.ui.RunContentDescriptor? = null
        var process: EmmyAttachDebugProcess? = null
        EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
            session = XDebuggerManager.getInstance(project).startSessionAndShowTab(
                name, object : XDebugProcessStarter() {
                    override fun start(current: XDebugSession): XDebugProcess =
                        EmmyAttachDebugProcess(current).also { process = it }
                }, environment)
            descriptor = session?.runContentDescriptor
        })
        val attachedSession = AttachedSession(
            checkNotNull(session) { "attach session was not created" },
            descriptor,
            checkNotNull(process) { "attach process was not created" }
        )
        attached += attachedSession
        return attachedSession
    }

    private fun disposeAttached(attached: List<AttachedSession>) {
        attached.forEach { item ->
            runCatching {
                EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
                    if (!item.session.isStopped) item.session.stop()
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                })
            }
            runCatching {
                EdtTestUtil.runInEdtAndWait<Throwable>(ThrowableRunnable {
                    item.descriptor?.let { runDescriptor ->
                        RunContentManager.getInstance(project).removeRunContent(
                            DefaultDebugExecutor.getDebugExecutorInstance(), runDescriptor)
                        if (!Disposer.isDisposed(runDescriptor)) Disposer.dispose(runDescriptor)
                    }
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                })
            }
        }
    }

    private fun assertOk(response: JsonObject) {
        assertTrue(response.toString(), response.get("ok")?.asBoolean == true)
    }

    private fun copyJson(value: JsonObject): JsonObject =
        JsonParser.parseString(value.toString()).asJsonObject

    private fun command(child: Process, value: String) {
        if (!child.isAlive) return
        child.outputStream.write("$value\n".toByteArray(StandardCharsets.UTF_8))
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
        }, "Emmy attach fixture output").apply { isDaemon = true; start() }

        fun text(): String = synchronized(output) { output.toString() }

        fun readyPid(): Int? = Regex("\\\"ready\\\":true,\\\"pid\\\":(\\d+)")
            .find(text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        fun join() = reader.join(2_000)
    }

    private class GatewaySocket(
        descriptor: CliInstanceDescriptor,
        private val clientId: String
    ) : AutoCloseable {
        private val socket = if (descriptor.endpoint.startsWith("npipe://")) {
            Win32NamedPipeSocket("\\\\.\\pipe\\${descriptor.endpoint.removePrefix("npipe://")}")
        } else {
            val uri = java.net.URI(descriptor.endpoint)
            Socket(uri.host, uri.port)
        }
        private val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        private val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)

        init {
            writer.write("{\"token\":\"${File(descriptor.tokenFile).readText().trim()}\"}")
            writer.newLine()
            writer.flush()
            assertTrue(reader.readLine().contains("authenticated"))
        }

        fun request(operation: String, targetId: String, arguments: JsonObject, leaseId: String?): JsonObject {
            val request = JsonObject().apply {
                addProperty("requestId", "attach-${System.nanoTime()}")
                addProperty("operation", operation)
                addProperty("targetId", targetId)
                addProperty("clientId", clientId)
                leaseId?.let { addProperty("leaseId", it) }
                add("arguments", arguments)
            }
            writer.write(request.toString())
            writer.newLine()
            writer.flush()
            return JsonParser.parseString(checkNotNull(reader.readLine()) { "CLI connection closed" }).asJsonObject
        }

        fun waitEvents(targetId: String, cursor: Long, limit: Int): Pair<Long, List<JsonObject>> {
            val request = JsonObject().apply {
                addProperty("requestId", "attach-wait-${System.nanoTime()}")
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
            var next = cursor
            val events = mutableListOf<JsonObject>()
            while (true) {
                val response = JsonParser.parseString(checkNotNull(reader.readLine()) { "CLI wait closed" }).asJsonObject
                if (response.get("ok")?.asBoolean == false) {
                    assertEquals(response.toString(), "TIMEOUT", response.getAsJsonObject("error").get("code").asString)
                    return next to events
                }
                response.get("cursor")?.takeUnless { it.isJsonNull }?.asLong?.let { next = it }
                response.get("data")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { data ->
                    if (data.has("type")) events += data
                }
                if (response.get("done")?.asBoolean == true) return next to events
            }
        }

        override fun close() = socket.close()
    }

    private fun <T> waitFor(timeoutMillis: Long, value: () -> T?): T? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            value()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }
}

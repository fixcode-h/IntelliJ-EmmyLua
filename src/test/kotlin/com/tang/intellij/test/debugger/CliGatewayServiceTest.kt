package com.tang.intellij.test.debugger

import com.google.gson.JsonObject
import com.tang.intellij.lua.debugger.cli.CliGatewayService
import com.tang.intellij.lua.debugger.cli.CliInstanceDescriptor
import com.tang.intellij.lua.debugger.cli.CliJsonLines
import com.tang.intellij.lua.debugger.cli.CliTargetSummary
import com.tang.intellij.lua.debugger.cli.CliVmSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CliGatewayServiceTest {
    private val provider = {
        listOf(CliTargetSummary("target-1", "Demo", "RUNNING", true,
            listOf(CliVmSummary("vm-1", 1, "PIE", "PAUSED", "5.4.3", "HOST_API", 3))))
    }

    @Test
    fun `read-only gateway lists targets and VMs`() {
        val gateway = CliGatewayService(provider)
        val target = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r1\",\"operation\":\"target.list\"}"))
        assertTrue(target.ok)
        assertEquals("target-1", target.data?.getAsJsonArray("targets")?.get(0)?.asJsonObject?.get("targetId")?.asString)

        val vms = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r2\",\"operation\":\"vm.list\",\"targetId\":\"target-1\"}"))
        assertEquals("vm-1", vms.data?.getAsJsonArray("vms")?.get(0)?.asJsonObject?.get("vmId")?.asString)
    }

    @Test
    fun `gateway rejects missing target and redacts untrusted projects`() {
        val gateway = CliGatewayService(provider) { false }
        val response = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r1\",\"operation\":\"target.status\"}"))
        assertFalse(response.ok)
        assertEquals("TARGET_REQUIRED", response.error?.code)

        val list = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r2\",\"operation\":\"target.list\"}"))
        assertEquals("<redacted>", list.data?.getAsJsonArray("targets")?.get(0)?.asJsonObject?.get("projectName")?.asString)
    }

    @Test
    fun `descriptor janitor writes atomically and removes stale PID`() {
        val dir = Files.createTempDirectory("emmy-cli-test")
        val path = dir.resolve("instance.json")
        val janitor = com.tang.intellij.lua.debugger.cli.CliEndpointJanitor(path)
        janitor.write(CliInstanceDescriptor(ideaInstanceId = "i1", pid = 42, product = "Rider", endpoint = "npipe://x", startedAt = "now", tokenFile = "token"))
        assertEquals("i1", janitor.read()?.ideaInstanceId)
        assertTrue(janitor.removeIfStale { false })
        assertFalse(Files.exists(path))
    }
}

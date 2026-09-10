package com.tang.intellij.test.debugger

import com.google.gson.JsonObject
import com.tang.intellij.lua.debugger.cli.CliGatewayService
import com.tang.intellij.lua.debugger.cli.CliInstanceDescriptor
import com.tang.intellij.lua.debugger.cli.CliJsonLines
import com.tang.intellij.lua.debugger.cli.CliTargetSummary
import com.tang.intellij.lua.debugger.cli.CliVmSummary
import com.tang.intellij.lua.debugger.cli.CliResponse
import com.tang.intellij.lua.debugger.cli.AuthorizationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        val gateway = CliGatewayService(provider, trustedProject = { false })
        val response = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r1\",\"operation\":\"target.status\"}"))
        assertFalse(response.ok)
        assertEquals("TARGET_REQUIRED", response.error?.code)

        val list = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r2\",\"operation\":\"target.list\"}"))
        assertEquals("<redacted>", list.data?.getAsJsonArray("targets")?.get(0)?.asJsonObject?.get("projectName")?.asString)
    }

    @Test
    fun `request cache is isolated by client and rechecks revoked access`() {
        val authorization = AuthorizationService()
        authorization.grant("target-1", "client-a")
        val gateway = CliGatewayService(provider, authorization = authorization)
        val first = gateway.handle(CliJsonLines.decodeRequest(
            "{\"requestId\":\"same\",\"clientId\":\"client-a\",\"operation\":\"target.status\",\"targetId\":\"target-1\"}"
        ))
        assertTrue(first.ok)

        val otherClient = gateway.handle(CliJsonLines.decodeRequest(
            "{\"requestId\":\"same\",\"clientId\":\"client-b\",\"operation\":\"target.status\",\"targetId\":\"target-1\"}"
        ))
        assertFalse(otherClient.ok)
        assertEquals("NOT_AUTHORIZED", otherClient.error?.code)

        authorization.revoke("target-1", "client-a")
        val revoked = gateway.handle(CliJsonLines.decodeRequest(
            "{\"requestId\":\"same\",\"clientId\":\"client-a\",\"operation\":\"target.status\",\"targetId\":\"target-1\"}"
        ))
        assertFalse(revoked.ok)
        assertEquals("NOT_AUTHORIZED", revoked.error?.code)
    }

    @Test
    fun `concurrent duplicate request is admitted once`() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateway = CliGatewayService({
            calls.incrementAndGet()
            entered.countDown()
            release.await(1, TimeUnit.SECONDS)
            provider()
        })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val request = CliJsonLines.decodeRequest(
                "{\"requestId\":\"concurrent\",\"clientId\":\"client-a\",\"operation\":\"target.list\"}"
            )
            val first = pool.submit<CliResponse> { gateway.handle(request) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val second = pool.submit<CliResponse> { gateway.handle(request) }.get(1, TimeUnit.SECONDS)
            assertEquals("REQUEST_IN_PROGRESS", second.error?.code)
            release.countDown()
            assertTrue(first.get(1, TimeUnit.SECONDS).ok)
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
            gateway.close()
        }
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

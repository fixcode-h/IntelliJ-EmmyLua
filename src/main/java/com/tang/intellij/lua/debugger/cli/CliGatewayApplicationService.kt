package com.tang.intellij.lua.debugger.cli

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.Disposable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

/** IDEA application-level owner for the local emmy-debug gateway. */
@Service(Service.Level.APP)
class CliGatewayApplicationService : Disposable {
    private val log = Logger.getInstance(CliGatewayApplicationService::class.java)
    private val instanceId = "idea-${UUID.randomUUID()}"
    private val registry = DebugTargetRegistry()
    private val authorization = AuthorizationService()
    private val leases = ControlLeaseManager()
    private val probes = AiProbeService(registry)
    private val token = generateToken()
    /** Shared with the standalone emmy-debug CLI; configurable for tests/CI. */
    private val root: Path = CliInstanceDirectory.root()
    private val tokenPath: Path = root.resolve("$instanceId.token")
    private val descriptorPath: Path = root.resolve("$instanceId.json")
    private val janitor = CliEndpointJanitor(descriptorPath)
    private var authorizationListener: AutoCloseable? = null
    private var registryListener: AutoCloseable? = null
    private var server: CliGatewayServer? = null
    private var gateway: CliGatewayService? = null
    private var descriptor: CliInstanceDescriptor? = null

    init {
        authorizationListener = authorization.addListener { targetId, clientId, granted ->
            if (!granted) {
                probes.onGrantRevoked(targetId, clientId)
                // A grant is a prerequisite for a control lease. Revoke the
                // lease at the same boundary so a client cannot keep issuing
                // controls after its target permission was withdrawn.
                leases.revokeOwner(clientId)
            }
        }
        registryListener = registry.addListener { event ->
            if (event.type == "target.removed") probes.onTargetClosed(event.targetId)
        }
        start()
    }

    fun register(process: EmmyDebugBackend): String {
        val adapter = EmmyDebugTargetAdapter(process)
        registry.register(adapter)
        return adapter.targetId
    }

    fun register(adapter: DebugTargetAdapter): Boolean = registry.register(adapter)
    fun unregister(targetId: String): Boolean = registry.unregister(targetId)
    fun grant(targetId: String, clientId: String) = authorization.grant(targetId, clientId)
    fun revoke(targetId: String, clientId: String) {
        authorization.revoke(targetId, clientId)
        leases.revokeOwner(clientId)
        probes.onGrantRevoked(targetId, clientId)
    }
    fun notifyUserControl(targetId: String) = probes.notifyUserControl(targetId)
    fun notifyVmClosing(targetId: String, vmId: String) = probes.onVmClosing(targetId, vmId)
    fun notifyVmClosed(targetId: String, vmId: String) = probes.onVmClosed(targetId, vmId)
    fun notifyVmContextReset(targetId: String, vmId: String) = probes.onVmContextReset(targetId, vmId)
    fun notifyTargetDisconnected(targetId: String) = probes.onTargetDisconnected(targetId)
    fun registry(): DebugTargetRegistry = registry
    fun authorization(): AuthorizationService = authorization
    fun leases(): ControlLeaseManager = leases
    fun endpoint(): CliInstanceDescriptor? = descriptor
    fun gatewayServer(): CliGatewayServer? = server

    private fun start() {
        var localServer: CliGatewayServer? = null
        var localGateway: CliGatewayService? = null
        runCatching {
            Files.createDirectories(root)
            CliEndpointJanitor.removeStaleDescriptors(root)
            janitor.removeIfStale()
            Files.writeString(tokenPath, token, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            CliEndpointJanitor.restrictToOwner(tokenPath)
            val gatewayService = CliGatewayService(
                provider = registry,
                // Target-scoped checks use DebugTargetAdapter.projectTrusted;
                // this fallback only governs instance metadata when no target
                // is selected yet.
                trustedProject = { registry.adapters().all { it.projectTrusted } },
                targetRegistry = registry,
                authorization = authorization,
                leases = leases,
                probes = probes,
                instanceList = { listDescriptors() }
            )
            localGateway = gatewayService
            val gatewayServer = CliGatewayServer(gatewayService, token)
            localServer = gatewayServer
            val endpoint = if (SystemInfoRt.isWindows) {
                runCatching { gatewayServer.startNamedPipe("emmylua-${ProcessHandle.current().pid()}-${instanceId.removePrefix("idea-").take(8)}") }
                    .getOrElse { error ->
                        log.warn("命名管道启动失败，回退 TCP: ${error.message}")
                        gatewayServer.start()
                    }
            } else gatewayServer.start()
            server = gatewayServer
            gateway = gatewayService
            descriptor = CliInstanceDescriptor(
                ideaInstanceId = instanceId,
                pid = ProcessHandle.current().pid(),
                product = System.getProperty("idea.product", "IntelliJ"),
                endpoint = endpoint.endpoint,
                startedAt = OffsetDateTime.now().toString(),
                tokenFile = tokenPath.toString()
            )
            janitor.write(requireNotNull(descriptor))
        }.onFailure { error ->
            log.warn("Emmy CLI Gateway 启动失败，外部 CLI 将不可用", error)
            runCatching { localServer?.close() }
            server = null
            runCatching { localGateway?.close() }
            gateway = null
            descriptor = null
            runCatching { janitor.deleteOwnDescriptor() }
            runCatching { Files.deleteIfExists(tokenPath) }
        }
    }

    private fun listDescriptors(): List<CliInstanceDescriptor> {
        val discovered = CliInstanceDirectory.descriptorFiles()
            .mapNotNull(CliInstanceDirectory::read)
            .filter { it.pid > 0 && it.endpoint.isNotBlank() }
        return (discovered + listOfNotNull(descriptor))
            .distinctBy { it.ideaInstanceId }
            .sortedBy { it.ideaInstanceId }
    }

    override fun dispose() {
        runCatching { server?.close() }
        server = null
        runCatching { gateway?.close() }
        gateway = null
        probes.close()
        leases.clear()
        authorization.clear()
        authorizationListener?.close()
        authorizationListener = null
        registryListener?.close()
        registryListener = null
        registry.close()
        runCatching { janitor.deleteOwnDescriptor() }
        runCatching { Files.deleteIfExists(tokenPath) }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        fun getInstance(): CliGatewayApplicationService =
            ApplicationManager.getApplication().getService(CliGatewayApplicationService::class.java)
    }
}

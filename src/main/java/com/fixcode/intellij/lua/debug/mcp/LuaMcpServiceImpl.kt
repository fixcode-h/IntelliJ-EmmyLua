package com.fixcode.intellij.lua.debug.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.fixcode.intellij.lua.debug.api.LuaMcpCallRecord
import com.fixcode.intellij.lua.debug.api.LuaMcpClient
import com.fixcode.intellij.lua.debug.api.LuaMcpClientStatus
import com.fixcode.intellij.lua.debug.api.LuaMcpInstallResult
import com.fixcode.intellij.lua.debug.api.LuaMcpServerState
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.fixcode.intellij.lua.debugger.mcp.BreakpointFilter
import com.fixcode.intellij.lua.debugger.mcp.install.McpClientKind
import com.fixcode.intellij.lua.debugger.mcp.install.McpInstallOutcome
import com.fixcode.intellij.lua.debugger.mcp.install.McpInstaller
import com.fixcode.intellij.lua.debugger.mcp.install.VcsRoot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * [LuaMcpService] 的实现：UI（设置页、状态栏、未安装提示、Agent 页）只认这个接口。
 * 状态都在应用级的 [LuaMcpServer] 里，这个类本身无状态，由扩展点创建。
 */
class LuaMcpServiceImpl : LuaMcpService {
    private val log = Logger.getInstance(LuaMcpServiceImpl::class.java)
    private val installer = McpInstaller()

    private val server: LuaMcpServer get() = LuaMcpServer.getInstance()

    override fun serverState(): LuaMcpServerState {
        val s = server
        return LuaMcpServerState(
            enabled = LuaDebugSettings.getInstance().mcpEnabled,
            running = s.isRunning,
            port = s.port,
            connectedClients = if (s.isRunning) s.mcp.protocol.activeClientCount() else 0,
            error = s.error,
        )
    }

    override fun applySettings() = server.applySettings()

    override fun addListener(listener: () -> Unit, parent: Disposable) {
        val sub = server.addListener(listener)
        Disposer.register(parent) { sub.close() }
    }

    /**
     * 安装位置，第一个是推荐的：[VcsRoot.installRoot] 算出来的根（版本管理根；没有版本管理的 UE 项目用含 .uproject 的目录），
     * 然后是 IDE 已识别的、包含项目目录的 VCS 根（IDE 配了 VCS 映射但目录里没有标记文件时），最后是项目里的其他 VCS 根（多仓库项目）。
     */
    override fun installRoots(project: Project): List<Path> {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        val vcsRoots = runCatching {
            ReadAction.compute<List<Path>, RuntimeException> {
                ProjectLevelVcsManager.getInstance(project).allVersionedRoots.map { VfsUtilCore.virtualToIoFile(it).toPath().toAbsolutePath().normalize() }
            }
        }.getOrDefault(emptyList())
        val roots = LinkedHashSet<Path>()
        if (base != null) {
            roots.add(VcsRoot.installRoot(base) { p4ClientRoot(base) })
            vcsRoots.filter { base.startsWith(it) }.sortedBy { it.nameCount }.forEach { roots.add(it) }
        }
        vcsRoots.filter { base == null || !base.startsWith(it) }.forEach { roots.add(it) }
        if (base != null) roots.add(base)
        return roots.toList()
    }

    override fun installStatus(project: Project, root: Path): List<LuaMcpClientStatus> =
        installer.status(root, McpInstaller.url(server.port)).map { s ->
            LuaMcpClientStatus(
                client = s.client.toApi(),
                configFile = s.configFile,
                detected = s.detected,
                installed = s.installed,
                outdated = s.outdated,
                error = s.error,
            )
        }

    override fun install(project: Project, root: Path, clients: Collection<LuaMcpClient>): List<LuaMcpInstallResult> {
        val results = installer.install(root, McpInstaller.url(server.port), clients.map { it.toKind() }).map { it.toApi() }
        results.forEach { log.info("mcp.install client=${it.client} file=${it.configFile} success=${it.success} ${it.message.orEmpty()}") }
        server.fireChanged()
        return results
    }

    override fun uninstall(project: Project, root: Path, clients: Collection<LuaMcpClient>): List<LuaMcpInstallResult> {
        val results = installer.uninstall(root, clients.map { it.toKind() }).map { it.toApi() }
        server.fireChanged()
        return results
    }

    override fun recentCalls(limit: Int): List<LuaMcpCallRecord> =
        server.mcp.callLog.recent(limit).map {
            LuaMcpCallRecord(
                timestampMillis = it.timestampMillis,
                tool = it.tool,
                argsSummary = it.argsSummary,
                resultSummary = it.resultSummary,
                success = it.success,
                clientName = it.clientName,
                sessionId = it.sessionId,
            )
        }

    override fun revokeAgentControl() {
        server.mcp.policy.revokeAll()
        log.info("mcp.control.revoked")
    }

    override fun clearAgentBreakpoints(project: Project) {
        val backend = LuaMcpBackendProvider.backendFor(project) ?: return
        backend.removeBreakpoints(BreakpointFilter(all = true)).whenComplete { n, e ->
            if (e != null) log.warn("mcp.breakpoints.clear.failed", e) else log.info("mcp.breakpoints.cleared count=$n")
        }
    }

    /** 没有 P4CONFIG 文件的 Perforce 工作区：问 p4 自己（和 UEToVS 一样），2 秒超时，没装 p4 就算了。 */
    private fun p4ClientRoot(dir: Path): Path? = try {
        val process = ProcessBuilder("p4", "-ztag", "-F", "%clientRoot%", "info")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            val out = process.inputStream.bufferedReader().readText().trim().lines().firstOrNull()?.trim()
            if (process.exitValue() == 0 && !out.isNullOrEmpty() && !out.startsWith("Perforce client error")) {
                Path.of(out).takeIf { Files.isDirectory(it) }
            } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun LuaMcpClient.toKind(): McpClientKind = McpClientKind.valueOf(name)

    private fun McpClientKind.toApi(): LuaMcpClient = LuaMcpClient.valueOf(name)

    private fun McpInstallOutcome.toApi() = LuaMcpInstallResult(
        client = client.toApi(),
        configFile = configFile,
        success = success,
        message = message,
        manualSnippet = manualSnippet,
        created = created,
    )
}

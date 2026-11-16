/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.debug.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.tang.intellij.lua.debug.api.LuaMcpCallRecord
import com.tang.intellij.lua.debug.api.LuaMcpClient
import com.tang.intellij.lua.debug.api.LuaMcpClientStatus
import com.tang.intellij.lua.debug.api.LuaMcpInstallResult
import com.tang.intellij.lua.debug.api.LuaMcpServerState
import com.tang.intellij.lua.debug.api.LuaMcpService
import com.tang.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.tang.intellij.lua.debugger.mcp.BreakpointFilter
import com.tang.intellij.lua.debugger.mcp.install.McpClientKind
import com.tang.intellij.lua.debugger.mcp.install.McpInstallOutcome
import com.tang.intellij.lua.debugger.mcp.install.McpInstaller
import com.tang.intellij.lua.debugger.mcp.install.VcsRoot
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
     * 版本管理根目录：IDE 已识别的 VCS 根里包含项目目录的那些（最外层在前，通常就是 .git 所在目录）；
     * 再加上按 UEToVS 规则往上找到的根（IDE 没配 VCS 映射、或 P4 只有 P4CONFIG 文件时）；都没有就是项目目录。
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
            // 包含项目目录的根优先（项目在仓库子目录里时，配置要写到仓库根，Agent 一般在那里启动）
            vcsRoots.filter { base.startsWith(it) }.sortedBy { it.nameCount }.forEach { roots += it }
            roots += VcsRoot.installRoot(base) { p4ClientRoot(base) }
        }
        // 项目里面的其他 VCS 根（多仓库项目）
        vcsRoots.filter { base == null || !base.startsWith(it) }.forEach { roots += it }
        if (roots.isEmpty() && base != null) roots += base
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

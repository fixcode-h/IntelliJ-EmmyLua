package com.fixcode.intellij.lua.onboarding

import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.ui.mcp.LuaMcpInstallDialog
import com.fixcode.intellij.lua.debug.ui.mcp.LuaMcpInstallPrompt
import com.fixcode.intellij.lua.lsp.EmmyLuaServer
import com.fixcode.intellij.lua.lsp.EmmyLuaServerLocator
import com.fixcode.intellij.lua.lsp.EmmyLuaWorkspaceConfig
import com.fixcode.intellij.lua.project.LuaProjectSettings
import com.fixcode.intellij.lua.project.LuaSettings
import com.fixcode.intellij.lua.project.LuaSettingsConfigurable
import com.fixcode.intellij.lua.project.UELinkClient
import com.fixcode.intellij.lua.project.UnrealDecl
import com.fixcode.intellij.lua.project.UnrealIntelliSenseRoots
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import java.nio.file.Files
import java.nio.file.Path

/**
 * 「EmmyLua 上手检查」的各项：语言服务（emmylua_ls）、UE 声明来源、UELink 后台生成、MCP 安装到项目。
 * 状态都用现有的判断（[EmmyLuaServerLocator]、LSP4IJ 的服务器状态、[UnrealIntelliSenseRoots]、[UELinkClient]、
 * [LuaMcpService.installStatus]），这里只把它们排成一页，再给每项配上一键处理的按钮。
 *
 * [check] 会读文件、看进程、读 MCP 客户端配置，在后台线程调；按钮的 [Fix.run] 在 EDT 上调。
 */
object LuaOnboarding {
    enum class Status {
        /** 已就绪 */
        OK,
        /** 待处理：气泡里只数这一种 */
        TODO,
        /** 可选 / 不影响使用，只是说明 */
        OPTIONAL,
    }

    class Fix(val label: String, val run: (Project) -> Unit)

    data class Item(val title: String, val status: Status, val detail: String, val fixes: List<Fix> = emptyList())

    const val DOC_UE_DECL = "https://github.com/fixcode-h/IntelliJ-EmmyLua/blob/main/docs/user/ue-declarations.md"
    const val DOC_MCP = "https://github.com/fixcode-h/IntelliJ-EmmyLua/blob/main/docs/mcp-debug-guide.md"

    /** 项目目录在 UE 项目里（往上找得到 .uproject）。只看几层目录，很便宜。 */
    fun isUnrealProject(project: Project): Boolean = !project.isDisposed && UnrealDecl.ueRoot(project) != null

    fun check(project: Project): List<Item> {
        if (project.isDisposed) return emptyList()
        val items = mutableListOf(languageServer(project))
        UnrealDecl.ueRoot(project)?.let { ue ->
            val declOk = items.addDeclarations(project, ue)
            items += uelinkGeneration(project, ue, declOk)
        }
        items += mcp(project)
        return items
    }

    // ------------------------------------------------------------------ 语言服务

    private fun languageServer(project: Project): Item {
        val title = "语言服务（emmylua_ls）"
        val described = EmmyLuaServerLocator.describe()
        if (described == null) {
            val configured = LuaSettings.instance.languageServerPath.isNotBlank()
            val why = if (configured) "设置里填的 emmylua_ls 路径不存在" else "没找到 emmylua_ls：插件自带的不在，PATH 里也没有"
            return Item(title, Status.TODO, "$why。跳转、补全、诊断都靠它。", listOf(openLanguageSettings()))
        }
        val status = runCatching { LanguageServerManager.getInstance(project).getServerStatus(EmmyLuaServer.ID) }.getOrNull()
        return when (status) {
            ServerStatus.started -> Item(title, Status.OK, "运行中：$described")
            ServerStatus.starting, ServerStatus.checking_installed, ServerStatus.installing, ServerStatus.installed ->
                Item(title, Status.OK, "正在启动：$described")
            ServerStatus.stopped, ServerStatus.stopping -> Item(
                title, Status.TODO,
                "已停止（可能启动失败）：$described。出错原因在 IDE 的「语言服务器」视图（LSP4IJ）里看日志。",
                listOf(Fix("重新启动") { EmmyLuaServer.restart(it) }, openLanguageSettings()),
            )
            // 还没启动过：LSP4IJ 打开 Lua 文件时才启动，不算问题
            else -> Item(
                title, Status.OK, "已找到：$described；打开 Lua 文件时自动启动。",
                listOf(Fix("现在启动") { LanguageServerManager.getInstance(it).start(EmmyLuaServer.ID) }),
            )
        }
    }

    // ------------------------------------------------------------------ UE 声明

    /** 加一项「UE 声明来源」，返回是否已经有声明目录。 */
    private fun MutableList<Item>.addDeclarations(project: Project, ue: Path): Boolean {
        val title = "UE 声明（UE 类型的跳转和补全）"
        val settings = LuaProjectSettings.getInstance(project)
        if (!settings.detectUnrealProject) {
            add(Item(
                title, Status.TODO,
                "UE 项目：$ue。设置里关掉了「自动探测 UE 项目」，UE.xxx、蓝图类型不会补全和跳转。",
                listOf(
                    Fix("开启自动探测") {
                        LuaProjectSettings.getInstance(it).detectUnrealProject = true
                        EmmyLuaServer.restart(it)
                    },
                    openLanguageSettings(),
                ),
            ))
            return false
        }
        val uelink = UnrealIntelliSenseRoots.uelinkDeclDir(ue)
        if (uelink != null) {
            add(Item(title, Status.OK, "用 UELink 后台生成的声明：$uelink", listOf(revealDir(uelink))))
            return true
        }
        val root = EmmyLuaWorkspaceConfig.projectRoot(project)
        val unlua = root?.let { UnrealIntelliSenseRoots.find(it) }.orEmpty()
            .filter { it.fileName?.toString().equals("IntelliSense", ignoreCase = true) }
        if (unlua.isNotEmpty()) {
            add(Item(
                title, Status.OK,
                "用 UnLua 的 IntelliSense 声明：" + unlua.joinToString("、"),
                listOf(revealDir(unlua.first())),
            ))
            return true
        }
        add(Item(
            title, Status.TODO,
            "UE 项目：$ue。还没有声明目录：在 UE 编辑器里用 UnLua 的「Generate IntelliSense」，或开启下面的 UELink 后台生成（推荐）。",
            listOf(openDoc("IntelliSense 目录说明", DOC_UE_DECL)),
        ))
        return false
    }

    private fun uelinkGeneration(project: Project, ue: Path, declOk: Boolean): Item {
        val title = "UELink 后台生成声明（推荐）"
        val enabled = LuaProjectSettings.getInstance(project).unrealLuaDecl
        val generated = UnrealIntelliSenseRoots.uelinkDeclDir(ue) != null
        val editor = runCatching { UELinkClient.findEditor(ue) }.getOrNull()
        val enable = Fix("开启后台生成") { UnrealDecl.setEnabledFromIde(it, true) }
        return when {
            enabled && generated -> Item(
                title, Status.OK,
                "已开启：蓝图编译、资源保存、Live Coding 后只更新变了的文件。" +
                    if (editor == null) "UE 编辑器没开，打开后自动接着更新。" else "",
                listOf(Fix("现在刷新") { p -> UnrealDecl.runInBackground(p) { UnrealDecl.refresh(p, UnrealDecl.Refresh.CHANGED) } }),
            )
            enabled && editor != null -> Item(
                title, Status.TODO, "已开启，还没生成过：UE 编辑器开着，可以现在生成（第一次要几十秒）。",
                listOf(Fix("现在生成") { p ->
                    UnrealDecl.runInBackground(p) { UnrealDecl.refresh(p, UnrealDecl.Refresh.CHANGED, enabledInUi = true) }
                }),
            )
            enabled -> Item(title, Status.OPTIONAL, "已开启，等 UE 编辑器打开后自动开始生成（第一次要几十秒）。")
            hasUELink(ue) -> Item(
                // 已经有 UnLua 的声明时只是推荐；什么声明都没有时这是最省事的办法
                title, if (declOk) Status.OPTIONAL else Status.TODO,
                "项目里装了 UELink（UEKit）。开启后 UE 编辑器在后台生成 C++ 类和蓝图的声明，改了自动更新，不用再手动点 Generate IntelliSense。" +
                    if (editor == null) "UE 编辑器没开时先记下，打开后自动开始。" else "",
                listOf(enable),
            )
            else -> Item(
                title, Status.OPTIONAL,
                "项目里没找到 UELink 2.5+（UEKit 插件库）。装上后可以让 UE 编辑器自动生成、增量更新声明；不装用 UnLua 的 IntelliSense 也行。",
                listOf(openDoc("查看说明", DOC_UE_DECL)),
            )
        }
    }

    /** 项目里有 UELink：插件目录（可能多套一层分类目录）有 UELink.uplugin，或者 UE 进程写过名片。 */
    private fun hasUELink(ue: Path): Boolean {
        if (Files.isDirectory(UELinkClient.instancesDir(ue))) return true
        val plugins = ue.resolve("Plugins")
        if (Files.isRegularFile(plugins.resolve("UELink").resolve("UELink.uplugin"))) return true
        return runCatching {
            Files.list(plugins).use { dirs ->
                dirs.anyMatch { Files.isRegularFile(it.resolve("UELink").resolve("UELink.uplugin")) }
            }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ MCP

    private fun mcp(project: Project): Item {
        val title = "MCP（让 Agent 调试 Lua、查代码）"
        val service = LuaMcpService.getInstance()
        val state = service?.serverState()
        if (service == null || state == null || !state.available) {
            return Item(title, Status.OPTIONAL, "IDE 的 MCP Server 插件没启用：只是没有给 Agent 用的工具，调试照常。")
        }
        val root = service.installRoots(project).firstOrNull()
            ?: return Item(title, Status.OPTIONAL, "找不到可以安装的目录（项目没有本地目录）。")
        val statuses = runCatching { service.installStatus(project, root) }.getOrElse {
            return Item(title, Status.TODO, "读 Agent 配置失败：${it.message}", listOf(chooseClients()))
        }
        val installed = statuses.filter { it.installed && !it.outdated }
        val outdated = statuses.filter { it.outdated || it.legacy }
        val openServer = Fix("打开 MCP 服务器设置") { service.openServerSettings(it) }
        return when {
            installed.isNotEmpty() && state.running -> Item(
                title, Status.OK,
                "已写进 " + installed.joinToString("、") { it.client.displayName } + " 的配置（$root），IDE 的 MCP 服务器在运行。",
                listOf(chooseClients("管理…")),
            )
            installed.isNotEmpty() && outdated.isEmpty() -> Item(
                title, Status.TODO, "配置已经写好，只差 IDE 的 MCP 服务器没开：Agent 连不上。", listOf(openServer),
            )
            outdated.isNotEmpty() -> Item(
                title, Status.TODO,
                "需要更新：" + outdated.joinToString("、") { it.client.displayName } + " 里的地址和 IDE 现在的不一致或是旧版的。" +
                    if (state.running) "" else "IDE 的 MCP 服务器也没开，点更新会先打开它的设置。",
                listOf(Fix("一键更新") { LuaMcpInstallPrompt.installRecommended(it, root) }, chooseClients()),
            )
            else -> Item(
                title, Status.TODO,
                "还没安装到项目：Claude Code、Cursor 等 Agent 看不到 Lua 调试工具。安装位置：$root" +
                    if (state.running) "" else "；IDE 的 MCP 服务器也没开，点安装会先打开它的设置。",
                listOf(Fix("一键安装") { LuaMcpInstallPrompt.installRecommended(it, root) }, chooseClients(), openDoc("说明", DOC_MCP)),
            )
        }
    }

    // ------------------------------------------------------------------ 按钮

    private fun openLanguageSettings() = Fix("打开 EmmyLua 设置") {
        ShowSettingsUtil.getInstance().showSettingsDialog(it, LuaSettingsConfigurable::class.java)
    }

    private fun chooseClients(label: String = "选择客户端…") = Fix(label) { LuaMcpInstallDialog.show(it) }

    private fun revealDir(dir: Path) = Fix("打开目录") { RevealFileAction.openDirectory(dir) }

    private fun openDoc(label: String, url: String) = Fix(label) { BrowserUtil.browse(url) }
}

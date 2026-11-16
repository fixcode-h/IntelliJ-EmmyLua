package com.fixcode.intellij.lua.debug.ue

import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.fixcode.intellij.lua.project.UELinkClient
import com.fixcode.intellij.lua.project.UnrealDecl
import com.fixcode.intellij.lua.project.UnrealIntelliSenseRoots
import com.fixcode.intellij.lua.project.UnrealLuaModules
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 把改过的 Lua 推给开着的 UE 编辑器 / 游戏重新加载：经 UELink（UEKit）的 `lua.reload`，不走调试连接，没附加也能用。
 *
 * 三个入口共用这里：编辑器里的「重载到 UE」动作、保存后自动重载（设置里打开，批量保存合并成一次）、
 * MCP 的 `lua_debug_session action=reload`。
 */
@Service(Service.Level.PROJECT)
class UnrealLuaReloader(private val project: Project) : Disposable {

    enum class Reason { NO_UE_PROJECT, NO_INSTANCE, NOT_SUPPORTED }

    /** 整体没法重载（不是某个模块失败）。消息是给人看的中文，说清楚怎么办。 */
    class ReloadException(val reason: Reason, message: String, val ueRoot: Path? = null) : IOException(message)

    data class TargetOutcome(
        val instance: UELinkClient.Instance,
        val items: List<UnrealLuaModules.Item>,
        val note: String? = null,
        /** 整个实例失败（连不上、没有 lua.reload）。 */
        val error: String? = null,
        /** UE 那边没有 lua.reload（UELink 太旧或没装 UnLua）。 */
        val opMissing: Boolean = false,
    )

    data class Outcome(
        val modules: List<String>,
        val targets: List<TargetOutcome>,
        /** 换算不出模块名的文件 → 原因。 */
        val skipped: Map<String, String>,
    ) {
        val okCount: Int get() = targets.sumOf { t -> t.items.count { it.ok } }
        val failures: List<Pair<TargetOutcome, UnrealLuaModules.Item?>>
            get() = targets.flatMap { t -> if (t.error != null) listOf(t to null) else t.items.filter { !it.ok }.map { t to it } }
    }

    private val pending = LinkedHashSet<Path>()
    private var scheduled: ScheduledFuture<*>? = null
    /** 自动重载找不到 UE 实例时只提示一次，免得每次保存都弹。 */
    @Volatile
    private var autoNoInstanceShown = false

    override fun dispose() {
        synchronized(pending) { scheduled?.cancel(false); pending.clear() }
    }

    fun ueRootFor(files: List<Path>): Path? =
        UnrealDecl.ueRoot(project) ?: files.firstNotNullOfOrNull { f -> f.parent?.let { UnrealIntelliSenseRoots.findUnrealProjectRoot(it) } }

    /**
     * 阻塞调用（本机 HTTP），在后台线程里用。[files] 换算成模块名后和 [modules] 合并；[changed] 让 UE 重载所有改过的。
     * 找不到 UE 项目 / 实例、所有实例都没有 lua.reload 时抛 [ReloadException]。
     */
    fun reload(modules: List<String>, files: List<Path>, changed: Boolean): Outcome {
        val ueRoot = ueRootFor(files)
        val names = LinkedHashSet(modules)
        val skipped = LinkedHashMap<String, String>()
        for (file in files) {
            val name = UnrealLuaModules.moduleName(file, ueRoot)
            if (name == null) skipped[file.toString()] = "不在 ${UnrealLuaModules.SCRIPT_DIR} 下或不是 .lua 文件，换算不出 UnLua 模块名"
            else names += name
        }
        if (ueRoot == null) {
            throw ReloadException(Reason.NO_UE_PROJECT, "没找到 .uproject：项目目录不在 UE 项目里，没法热重载到 UE。")
        }
        if (names.isEmpty() && !changed) return Outcome(emptyList(), emptyList(), skipped)
        val targets = UnrealLuaModules.reloadTargets(UELinkClient.listInstances(ueRoot))
        if (targets.isEmpty()) {
            throw ReloadException(
                Reason.NO_INSTANCE,
                "没找到开着的 UE 编辑器或游戏：先打开 UE 编辑器；还没装 UELink 的话在 Agent 面板的 UE 页安装 UELink（2.4.0 以上，项目要用 UnLua）并编译。",
                ueRoot,
            )
        }
        val args = JsonObject()
        if (changed) args.addProperty("changed", true)
        else args.add("modules", JsonArray().apply { names.forEach { add(it) } })
        val results = targets.map { instance ->
            try {
                val reply = UnrealLuaModules.parseReloadReply(
                    UELinkClient.call(instance, "lua.reload", args, Duration.ofSeconds(30), RELOAD_MISSING_HINT)
                )
                TargetOutcome(instance, reply.items, reply.note)
            } catch (e: UELinkClient.OpMissingException) {
                TargetOutcome(instance, emptyList(), error = e.message, opMissing = true)
            } catch (e: IOException) {
                TargetOutcome(instance, emptyList(), error = e.message ?: e.toString())
            }
        }
        if (results.all { it.opMissing }) {
            throw ReloadException(Reason.NOT_SUPPORTED, results.first().error ?: RELOAD_MISSING_HINT, ueRoot)
        }
        return Outcome(names.toList(), results, skipped)
    }

    // ------------------------------------------------------------------ 动作 / 保存后自动重载

    /** 「重载到 UE」动作：后台调用，结果用通知显示。 */
    fun reloadFilesInBackground(files: List<Path>) {
        // 手动重载已经包含的文件，不用再等保存触发的那次
        synchronized(pending) { pending.removeAll(files.toSet()) }
        ApplicationManager.getApplication().executeOnPooledThread { runAndReport(files, auto = false) }
    }

    /** 保存了一个 Lua 文件：攒 [DEBOUNCE_MS] 毫秒，批量保存合并成一次调用。 */
    fun scheduleOnSave(file: Path) {
        synchronized(pending) {
            pending.add(file)
            scheduled?.cancel(false)
            scheduled = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (batch.isNotEmpty() && !project.isDisposed) {
                    ApplicationManager.getApplication().executeOnPooledThread { runAndReport(batch, auto = true) }
                }
            }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun runAndReport(files: List<Path>, auto: Boolean) {
        if (project.isDisposed) return
        val outcome = try {
            reload(emptyList(), files, changed = false)
        } catch (e: ReloadException) {
            when {
                !auto -> notifyProblem(e)
                // 自动重载：不在 UE 项目里就不打扰；找不到实例只提示一次
                e.reason == Reason.NO_UE_PROJECT -> Unit
                e.reason == Reason.NO_INSTANCE -> if (!autoNoInstanceShown) { autoNoInstanceShown = true; notifyProblem(e) }
                else -> notifyProblem(e)
            }
            return
        } catch (e: Exception) {
            LOG.warn("重载到 UE 失败", e)
            notify(NotificationType.ERROR, "重载到 UE 失败", e.message ?: e.toString())
            return
        }
        autoNoInstanceShown = false
        if (outcome.modules.isEmpty()) {
            if (!auto) notify(NotificationType.WARNING, "没有可重载的模块", outcome.skipped.entries.joinToString("<br>") { "${it.key}：${it.value}" })
            return
        }
        val failures = outcome.failures
        if (failures.isEmpty()) {
            val where = outcome.targets.joinToString("、") { describe(it.instance) }
            val summary = "已重载到 $where：${outcome.modules.joinToString("、").take(300)}"
            if (auto) statusBar(summary)
            else notify(NotificationType.INFORMATION, "已重载到 UE", summary + noteOf(outcome))
            return
        }
        val lines = failures.take(10).map { (t, item) ->
            if (item == null) "${describe(t.instance)}：${t.error}" else "${describe(t.instance)} · ${item.module}：${item.error ?: "失败"}"
        }
        val more = if (failures.size > 10) "<br>…还有 ${failures.size - 10} 处失败" else ""
        val ok = if (outcome.okCount > 0) "<br>其余 ${outcome.okCount} 个已重载。" else ""
        notify(NotificationType.WARNING, "重载到 UE：有模块失败", lines.joinToString("<br>") { escape(it) } + more + ok)
    }

    private fun noteOf(outcome: Outcome): String =
        outcome.targets.mapNotNull { it.note }.distinct().joinToString("<br>") { escape(it) }.let { if (it.isEmpty()) "" else "<br>$it" }

    private fun describe(instance: UELinkClient.Instance) = "${instance.label}(${instance.pid})"

    private fun notifyProblem(e: ReloadException) {
        val notification = group().createNotification("重载到 UE", escape(e.message.orEmpty()), NotificationType.WARNING)
        val uproject = e.ueRoot?.let { root -> runCatching { Files.list(root).use { s -> s.filter { it.toString().endsWith(".uproject", true) }.findFirst().orElse(null) } }.getOrNull() }
        if (e.reason == Reason.NO_INSTANCE && uproject != null) {
            notification.addAction(NotificationAction.createSimpleExpiring("打开 UE 编辑器") { openWithSystem(uproject) })
        }
        if (e.reason == Reason.NO_INSTANCE || e.reason == Reason.NOT_SUPPORTED) {
            notification.addAction(NotificationAction.createSimple("UELink 是什么") {
                com.intellij.ide.BrowserUtil.browse(UELINK_URL)
            })
        }
        notification.notify(project)
    }

    private fun notify(type: NotificationType, title: String, content: String) {
        group().createNotification(title, content, type).notify(project)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)

    private fun statusBar(text: String) = ApplicationManager.getApplication().invokeLater {
        if (!project.isDisposed) WindowManager.getInstance().getStatusBar(project)?.info = text
    }

    /** 双击 .uproject 的效果：系统默认程序（UE 启动器）打开它；打不开就在文件管理器里定位。 */
    private fun openWithSystem(file: Path) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val opened = runCatching {
                if (java.awt.Desktop.isDesktopSupported()) { java.awt.Desktop.getDesktop().open(file.toFile()); true } else false
            }.getOrDefault(false)
            if (!opened) RevealFileAction.openFile(file)
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private val LOG = logger<UnrealLuaReloader>()
        const val NOTIFICATION_GROUP = "EmmyLua UE Reload"
        const val DEBOUNCE_MS = 300L
        const val RELOAD_MISSING_HINT = "lua.reload 要 UELink 2.4.0 以上，并且项目里装了 UnLua；在 Agent 面板的 UE 页升级 UELink 后重新编译"
        private const val UELINK_URL = "https://github.com/fixcode-h/UEKit"

        fun getInstance(project: Project): UnrealLuaReloader = project.service()

        fun autoReloadEnabled(): Boolean = LuaDebugSettings.getInstance().reloadToUnrealOnSave
    }
}

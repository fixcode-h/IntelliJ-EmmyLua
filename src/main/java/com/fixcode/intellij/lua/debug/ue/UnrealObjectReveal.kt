package com.fixcode.intellij.lua.debug.ue

import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.DebugSession
import com.fixcode.intellij.lua.debug.protocol.VariableDto
import com.fixcode.intellij.lua.project.UELinkClient
import com.fixcode.intellij.lua.project.UnrealDecl
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 变量树右键「在 UE 中选中」「在 UE 中打开资产」：把 UObject 的路径交给 UE 编辑器里 UELink 的 `object.reveal`
 * （UEKit 协议文档 2 节，2.10.0 起）。路径来自 UELink 宿主值提供者给 UObject 行的 `hint`（`GetPathName()` 原文，
 * 协议文档 7 节），所以只有装了 UELink、UObject 由它显示时才有这两项。
 *
 * Lua 停在编辑器进程（PIE）的断点上时，游戏线程卡在调试器的暂停循环里，UELink 的 `/rpc` 也在游戏线程上执行，
 * 这时发过去要等继续运行才处理；编辑器界面同样是冻结的，选中了也看不到。所以这种情况先提示，等这个会话
 * 没有暂停了再发。调试的是独立游戏进程时编辑器不受影响，马上发（资源、蓝图都能定位；游戏进程里的 Actor
 * 编辑器里没有，UELink 会回原因）。
 */
object UnrealObjectReveal {
    enum class Action(val op: String, val label: String) {
        SELECT("select", "在 UE 中选中"),
        OPEN("open", "在 UE 中打开资产"),
    }

    /** 能用的编辑器，或者不能用的原因（菜单置灰时显示）。 */
    data class Target(val instance: UELinkClient.Instance?, val problem: String?)

    const val MIN_UELINK = "2.10.0"
    private const val OP = "object.reveal"
    private const val MISSING_HINT = "要 UELink $MIN_UELINK 以上：在 Agent 面板的 UE 页升级 UELink 后重新编译"
    private const val NOTIFICATION_GROUP = "EmmyLua Debug"
    private const val CACHE_MS = 2000L
    private val LOG = logger<UnrealObjectReveal>()

    /** UE 对象行的路径：kind 是 ueObject、没失效、hint 像 UE 路径（`/Game/...`、`/Script/...`）。遮蔽提示之类的 hint 不算。 */
    fun objectPathOf(v: VariableDto): String? {
        if (v.kind != "ueObject" || v.hasAttr("invalid")) return null
        val hint = v.hint?.trim() ?: return null
        if (hint.length < 2 || hint[0] != '/' || hint.any { it.isWhitespace() }) return null
        return hint
    }

    /** `2.10.0` ≥ `2.9.1` 这类比较：按数字逐段比，`-beta` 之类的后缀不看。版本缺失算不够。 */
    fun versionAtLeast(version: String?, min: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        if (version.isNullOrBlank()) return false
        val a = parts(version)
        val b = parts(min)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    private data class Cached(val at: Long, val instances: List<UELinkClient.Instance>)
    private val cache = ConcurrentHashMap<Path, Cached>()

    /** 菜单的 update 每次弹出都会调，读名片目录有 2 秒缓存。 */
    private fun instances(ueRoot: Path): List<UELinkClient.Instance> {
        val now = System.currentTimeMillis()
        cache[ueRoot]?.takeIf { now - it.at < CACHE_MS }?.let { return it.instances }
        return UELinkClient.listInstances(ueRoot).also { cache[ueRoot] = Cached(now, it) }
    }

    /**
     * 发给哪个编辑器：被调试的进程本身是编辑器就用它（PIE 里的 Actor 只在这个进程里），否则用最新启动的编辑器。
     * 编辑器之外的进程（-game、专用服务器）没有选中和资源编辑器。
     */
    fun target(project: Project, session: DebugSession?): Target {
        val ueRoot = UnrealDecl.ueRoot(project) ?: return Target(null, "项目不在 UE 项目里（没找到 .uproject）")
        val editors = instances(ueRoot).filter { it.kind == UELinkClient.KIND_EDITOR }
        if (editors.isEmpty()) return Target(null, "没找到开着的 UE 编辑器（或项目没装 UELink）")
        val pid = session?.welcome?.process?.pid
        val editor = editors.firstOrNull { it.pid == pid } ?: editors.first()
        if (!versionAtLeast(editor.version, MIN_UELINK)) {
            return Target(editor, "要 UELink $MIN_UELINK 以上（现在是 ${editor.version ?: "旧版"}），在 Agent 面板的 UE 页升级")
        }
        return Target(editor, null)
    }

    /** 后台执行；结果和错误用通知 / 状态栏显示。 */
    fun reveal(project: Project, session: DebugSession, path: String, name: String, action: Action) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val target = runCatching { target(project, session) }.getOrElse { Target(null, it.message ?: it.toString()) }
            val editor = target.instance
            if (editor == null || target.problem != null) {
                notify(project, NotificationType.WARNING, "${action.label}：${target.problem ?: "没找到 UE 编辑器"}")
                return@executeOnPooledThread
            }
            val frozen = session.welcome?.process?.pid == editor.pid && session.pauses().isNotEmpty()
            if (frozen) {
                notify(project, NotificationType.INFORMATION,
                    "Lua 停在 UE 编辑器的断点上，编辑器界面是冻结的：继续运行后再${action.label.removePrefix("在 UE 中")}「$name」。")
                whenNotPaused(session) { call(project, editor, path, name, action) }
            } else {
                call(project, editor, path, name, action)
            }
        }
    }

    /** 这个会话所有 VM 都继续运行（或会话结束）后在后台线程执行一次 [run]。 */
    private fun whenNotPaused(session: DebugSession, run: () -> Unit) {
        val fired = AtomicBoolean(false)
        val subscription = AtomicReference<AutoCloseable?>()
        fun fire(force: Boolean) {
            if (!force && session.pauses().isNotEmpty()) return
            if (!fired.compareAndSet(false, true)) return
            subscription.getAndSet(null)?.close()
            // 事件在会话线程上回调，不能在这里发 HTTP
            ApplicationManager.getApplication().executeOnPooledThread(run)
        }
        subscription.set(session.events.subscribe { event ->
            when (event) {
                is DebugEvent.Resumed -> fire(false)
                is DebugEvent.Terminated, is DebugEvent.Disconnected -> fire(true)
                else -> Unit
            }
        })
        if (fired.get()) subscription.getAndSet(null)?.close()
        // 订阅之前可能已经继续了
        fire(false)
    }

    private fun call(project: Project, editor: UELinkClient.Instance, path: String, name: String, action: Action) {
        if (project.isDisposed) return
        val args = JsonObject().apply {
            addProperty("path", path)
            addProperty("action", action.op)
        }
        val reply = try {
            UELinkClient.call(editor, OP, args, Duration.ofSeconds(15), MISSING_HINT)
        } catch (e: IOException) {
            notify(project, NotificationType.WARNING, "${action.label}「$name」失败：${e.message}")
            return
        } catch (e: Exception) {
            LOG.warn("${action.label}失败", e)
            notify(project, NotificationType.ERROR, "${action.label}「$name」失败：${e.message ?: e}")
            return
        }
        statusBar(project, describe(reply, action, name))
    }

    /** 回包 `{did, path, name, pie?}` → 状态栏一句话。 */
    internal fun describe(reply: JsonObject, action: Action, fallbackName: String): String {
        val did = reply.get("did")?.takeIf { it.isJsonPrimitive }?.asString
        val shown = reply.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: fallbackName
        return when (action) {
            Action.SELECT -> when (did) {
                "actor" -> "已在 UE 关卡里选中 $shown" + if (reply.get("pie")?.asBoolean == true) "（PIE）" else ""
                "blueprint" -> "已在 UE 内容浏览器里定位蓝图 $shown"
                else -> "已在 UE 内容浏览器里定位 $shown"
            }
            Action.OPEN -> if (did == "blueprint") "已在 UE 里打开蓝图 $shown" else "已在 UE 里打开 $shown"
        }
    }

    private fun notify(project: Project, type: NotificationType, message: String) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(escape(message), type)
            .notify(project)
    }

    private fun statusBar(project: Project, text: String) = ApplicationManager.getApplication().invokeLater {
        if (!project.isDisposed) WindowManager.getInstance().getStatusBar(project)?.info = text
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

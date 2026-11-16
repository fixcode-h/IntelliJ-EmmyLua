package com.fixcode.intellij.lua.debug.ui.process

import com.fixcode.intellij.lua.project.UELinkClient
import com.fixcode.intellij.lua.project.UnrealDecl
import com.intellij.openapi.project.ProjectManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程 → UELink 名片（`<UE项目>/Saved/UELink/instances/<PID>.json`）。附加列表用它把编辑器 / PIE / 游戏排在前面并标出项目名。
 *
 * 名片在哪个 UE 项目下，按三处找：打开的 IDE 项目所在的 UE 项目、命令行里的 `.uproject`、
 * 可执行文件所在的 `<项目>/Binaries/<平台>/`（打包后的游戏也是这个布局）。
 * 列表每次重绘都会问，所以按 PID 缓存几秒。
 */
object UELinkProcessCards {
    private const val TTL_MS = 5_000L

    private class Cached(val card: UELinkClient.Instance?, val at: Long)

    private val cache = ConcurrentHashMap<Int, Cached>()
    @Volatile
    private var openRoots: Pair<Long, List<Path>> = 0L to emptyList()

    fun cardFor(pid: Int, commandLine: String?, executablePath: String?): UELinkClient.Instance? {
        val now = System.currentTimeMillis()
        cache[pid]?.takeIf { now - it.at < TTL_MS }?.let { return it.card }
        val card = candidateRoots(commandLine, executablePath, openUnrealRoots(now))
            .firstNotNullOfOrNull { UELinkClient.findByPid(it, pid.toLong()) }
        cache[pid] = Cached(card, now)
        if (cache.size > 2048) cache.entries.removeIf { now - it.value.at >= TTL_MS }
        return card
    }

    private fun openUnrealRoots(now: Long): List<Path> {
        val (at, roots) = openRoots
        if (now - at < TTL_MS) return roots
        val fresh = runCatching {
            ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.mapNotNull { UnrealDecl.ueRoot(it) }.distinct()
        }.getOrDefault(emptyList())
        openRoots = now to fresh
        return fresh
    }

    private val UPROJECT_QUOTED = Regex("\"([^\"]+\\.uproject)\"", RegexOption.IGNORE_CASE)
    private val UPROJECT_BARE = Regex("(\\S+\\.uproject)", RegexOption.IGNORE_CASE)
    private val BINARIES = Regex("^(.*)/Binaries/[^/]+/[^/]+$", RegexOption.IGNORE_CASE)

    /** 可能放着这个进程名片的 UE 项目根，去重、保持顺序。纯函数，便于单测。 */
    fun candidateRoots(commandLine: String?, executablePath: String?, openRoots: List<Path>): List<Path> {
        val result = LinkedHashSet<Path>()
        commandLine?.let { cmd ->
            val uproject = UPROJECT_QUOTED.find(cmd)?.groupValues?.get(1) ?: UPROJECT_BARE.find(cmd)?.groupValues?.get(1)
            uproject?.let { p -> runCatching { Path.of(p.trim('"')).toAbsolutePath().parent }.getOrNull()?.let(result::add) }
        }
        executablePath?.replace('\\', '/')?.let { exe ->
            BINARIES.find(exe)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { Path.of(it) }.getOrNull() }?.let(result::add)
        }
        result.addAll(openRoots)
        return result.filter { runCatching { Files.isDirectory(UELinkClient.instancesDir(it)) }.getOrDefault(false) || it in openRoots }
            .distinct()
    }
}

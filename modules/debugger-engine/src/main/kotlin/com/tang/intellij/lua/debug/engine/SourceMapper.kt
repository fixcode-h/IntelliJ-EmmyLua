package com.tang.intellij.lua.debug.engine

import java.io.File
import java.security.MessageDigest

/** 路径规范化。规则沿用旧 SourceIdentity（相对路径保留开头的 `..`，绝对路径不能越过根）。 */
object SourcePaths {
    fun normalize(path: String): String {
        if (path.isBlank()) return ""
        val replaced = path.trim().replace('\\', '/')
        val hasDrive = replaced.length >= 2 && replaced[1] == ':' && replaced[0].isLetter()
        val driveRooted = hasDrive && replaced.length > 2 && replaced[2] == '/'
        val prefix = when {
            hasDrive -> replaced.substring(0, 2).lowercase() + if (driveRooted) "/" else ""
            replaced.startsWith("//") -> "//"
            replaced.startsWith('/') -> "/"
            else -> ""
        }
        val bodyText = when {
            hasDrive -> replaced.substring(if (driveRooted) 3 else 2)
            replaced.startsWith("//") -> replaced.substring(2)
            replaced.startsWith('/') -> replaced.substring(1)
            else -> replaced
        }
        val absolute = driveRooted || replaced.startsWith('/')
        val parts = ArrayDeque<String>()
        for (part in bodyText.split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." && parts.isNotEmpty() && parts.last() != ".." -> parts.removeLast()
                part == ".." && absolute -> Unit
                else -> parts.addLast(part)
            }
        }
        return prefix + parts.joinToString("/")
    }

    /** chunkName → 路径：去掉开头的 `@`；`=[C]`、`[string "..."]` 这类不是文件，返回 null。 */
    fun chunkToPath(chunkName: String): String? {
        if (chunkName.isEmpty() || chunkName.startsWith("=") || chunkName.startsWith("[")) return null
        return normalize(chunkName.removePrefix("@"))
    }

    /** 比较用的路径段：去掉扩展名 .lua/.luac/.txt/.bytes（协议 7.2），按需忽略大小写。 */
    fun segments(path: String, caseSensitive: Boolean): List<String> {
        val normalized = normalize(path).let { if (caseSensitive) it else it.lowercase() }
        val stripped = LUA_EXTENSIONS.firstOrNull { normalized.endsWith(it) }?.let { normalized.dropLast(it.length) } ?: normalized
        return stripped.split('/').filter { it.isNotEmpty() && !(it.length == 2 && it[1] == ':') }
    }

    fun isAbsolute(path: String): Boolean {
        val normalized = normalize(path)
        return normalized.startsWith("/") || (normalized.length > 2 && normalized[1] == ':' && normalized[2] == '/')
    }

    private val LUA_EXTENSIONS = listOf(".lua", ".luac", ".txt", ".bytes")
}

/** 本地文件查找，由宿主提供：主插件用项目文件索引，测试用内存实现。可能阻塞，引擎在 IO 线程调用。 */
interface SourceFileIndex {
    fun exists(path: String): Boolean

    /** 项目里路径以这些段结尾的文件（段已规范化，不带扩展名，大小写按 [caseSensitive]）。 */
    fun findBySuffix(segments: List<String>, caseSensitive: Boolean): List<String>

    /** 文件内容的 SHA-256（小写 hex）；读不到返回 null。 */
    fun sha256(path: String): String?

    companion object {
        /** 只看磁盘、不搜索项目的实现：给没有项目索引的场景（例如单测、命令行）兜底。 */
        val FILE_SYSTEM: SourceFileIndex = object : SourceFileIndex {
            override fun exists(path: String) = File(path).isFile
            override fun findBySuffix(segments: List<String>, caseSensitive: Boolean): List<String> = emptyList()
            override fun sha256(path: String): String? = sha256Of(File(path))
        }

        fun sha256Of(file: File): String? = try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/** 设备侧 chunkName 前缀 → 本地目录（设计文档 7.6）。 */
data class MappingRule(val remotePrefix: String, val localDirectory: String)

sealed class SourceResolution {
    data class Mapped(val localPath: String, val learned: Boolean = false) : SourceResolution()
    data class Ambiguous(val candidates: List<String>) : SourceResolution()
    object Unmapped : SourceResolution()
    /** `=[C]`、`[string "..."]` 等不是文件的 chunk。 */
    object NotAFile : SourceResolution()
}

data class MappingEntry(val chunkName: String, val resolution: SourceResolution)

/**
 * chunkName ↔ 本地文件。线程安全（UI 线程和会话线程都会查），查询结果按 chunkName 缓存。
 *
 * 查找顺序：用户指定（[override]）→ 规则表（最长前缀优先）→ chunkName 本身是存在的本地路径 →
 * 自动学习（按路径段从后往前匹配项目文件，唯一时采用并记成规则，多个时返回候选让用户选）。
 */
class SourceMapper(
    rules: List<MappingRule> = emptyList(),
    private val index: SourceFileIndex = SourceFileIndex.FILE_SYSTEM,
    private val autoLearn: Boolean = true,
    @Volatile var caseSensitive: Boolean = false
) {
    private val lock = Any()
    private val rules = ArrayList(rules.map { MappingRule(trimSlash(it.remotePrefix), SourcePaths.normalize(it.localDirectory)) })
    private val overrides = LinkedHashMap<String, String>()
    private val cache = LinkedHashMap<String, SourceResolution>()

    fun rules(): List<MappingRule> = synchronized(lock) { rules.toList() }

    fun entries(): List<MappingEntry> = synchronized(lock) { cache.map { MappingEntry(it.key, it.value) } }

    /** 用户在「映射」页给某个 chunk 手动指定本地文件。 */
    fun override(chunkName: String, localPath: String) = synchronized(lock) {
        overrides[chunkName] = SourcePaths.normalize(localPath)
        // 只影响这一个 chunk；全清会让「映射」页在下次暂停前变空
        cache.remove(chunkName)
    }

    fun addRule(rule: MappingRule) = synchronized(lock) {
        rules += MappingRule(trimSlash(rule.remotePrefix), SourcePaths.normalize(rule.localDirectory))
        cache.clear()
    }

    fun resolve(chunkName: String): SourceResolution {
        synchronized(lock) { cache[chunkName]?.let { return it } }
        val result = compute(chunkName)
        synchronized(lock) { cache[chunkName] = result }
        return result
    }

    /** 本地文件内容的 SHA-256，断点下发时给 runtime 对比热更新。 */
    fun sha256(localPath: String): String? = index.sha256(localPath)

    fun localPathOf(chunkName: String): String? = (resolve(chunkName) as? SourceResolution.Mapped)?.localPath

    /** 断点用：本地文件已知对应的 chunkName 时一并给出，runtime 就按它精确匹配。 */
    fun knownChunkFor(localPath: String): String? {
        val path = SourcePaths.normalize(localPath)
        val normalized = key(path)
        synchronized(lock) {
            overrides.entries.firstOrNull { key(it.value) == normalized }?.let { return it.key }
            cache.entries.firstOrNull { (_, resolution) ->
                resolution is SourceResolution.Mapped && key(resolution.localPath) == normalized
            }?.let { return it.key }
            // 反查规则表：设备上的 chunk 路径和本地路径段不一样（如 scripts/ ↔ Content/Script/）时，
            // 只靠 runtime 的后缀匹配对不上，必须把 chunkName 一起给它
            val rule = rules.filter { it.localDirectory.isNotEmpty() && startsWithDir(path, it.localDirectory) }
                .maxByOrNull { it.localDirectory.length } ?: return null
            val relative = path.substring(rule.localDirectory.length).trimStart('/')
            return if (rule.remotePrefix.isEmpty()) relative else "${rule.remotePrefix}/$relative"
        }
    }

    private fun startsWithDir(path: String, dir: String): Boolean =
        path.startsWith(dir, ignoreCase = !caseSensitive) && (path.length == dir.length || path[dir.length] == '/')

    private fun compute(chunkName: String): SourceResolution {
        val remote = SourcePaths.chunkToPath(chunkName) ?: return SourceResolution.NotAFile
        synchronized(lock) { overrides[chunkName] }?.let { return SourceResolution.Mapped(it) }

        val rule = synchronized(lock) {
            rules.filter { startsWithSegments(remote, it.remotePrefix) }.maxByOrNull { it.remotePrefix.length }
        }
        if (rule != null) {
            val relative = remote.substring(rule.remotePrefix.length).trimStart('/')
            val candidate = SourcePaths.normalize("${rule.localDirectory}/$relative")
            if (index.exists(candidate)) return SourceResolution.Mapped(candidate)
        }
        if (SourcePaths.isAbsolute(remote) && index.exists(remote)) return SourceResolution.Mapped(remote)
        if (!autoLearn) return SourceResolution.Unmapped

        val segments = SourcePaths.segments(remote, caseSensitive)
        if (segments.isEmpty()) return SourceResolution.Unmapped
        // 从最长后缀开始找：段越多越可靠。某个长度上出现多个候选时，更短的后缀只会更多，直接交给用户选
        for (length in segments.size downTo 1) {
            val matches = index.findBySuffix(segments.takeLast(length), caseSensitive).map(SourcePaths::normalize).distinct()
            when {
                matches.size == 1 -> {
                    learnRule(remote, matches.single())
                    return SourceResolution.Mapped(matches.single(), learned = true)
                }
                matches.size > 1 -> return SourceResolution.Ambiguous(matches.sorted())
            }
        }
        return SourceResolution.Unmapped
    }

    /** 学到一个对应关系后记成前缀规则：同目录下的其他 chunk 不用再搜索。 */
    private fun learnRule(remote: String, local: String) {
        val remoteParts = remote.split('/')
        val localParts = local.split('/')
        var common = 0
        while (common < remoteParts.size && common < localParts.size &&
            remoteParts[remoteParts.size - 1 - common].equals(localParts[localParts.size - 1 - common], !caseSensitive)
        ) common++
        if (common == 0 || common >= localParts.size) return
        // chunk 是完整的相对路径时学到的是空前缀规则：「所有相对 chunk 都在这个目录下」（UE 的常见情况）
        if (common >= remoteParts.size && SourcePaths.isAbsolute(remote)) return
        val remotePrefix = remoteParts.dropLast(common).joinToString("/")
        val localDir = localParts.dropLast(common).joinToString("/")
        if (localDir.isEmpty()) return
        synchronized(lock) {
            if (rules.none { it.remotePrefix == remotePrefix }) rules += MappingRule(remotePrefix, localDir)
        }
    }

    private fun startsWithSegments(path: String, prefix: String): Boolean {
        if (prefix.isEmpty()) return !SourcePaths.isAbsolute(path)
        if (!path.startsWith(prefix, ignoreCase = !caseSensitive)) return false
        return path.length == prefix.length || path[prefix.length] == '/'
    }

    private fun key(path: String) = if (caseSensitive) path else path.lowercase()

    private fun trimSlash(prefix: String) = SourcePaths.normalize(prefix.removePrefix("@")).trimEnd('/')
}

package com.fixcode.intellij.lua.debugger.android.elf

import java.io.File
import java.util.Properties

/**
 * 符号目录索引：在设置里的符号目录（可多个，常是构建产物归档、共享盘）下递归找 ELF，按 GNU Build ID 找同一次构建的未裁剪 so。
 *
 * 也认 `<目录>/.build-id/xx/yyyy.debug` 布局（GDB / LLDB / 符号服务器通用），这种布局直接按路径取，不用扫描。
 *
 * 扫描结果按「路径 + 大小 + 修改时间」缓存到 [cacheDir]：几十 GB 的归档目录第二次只做 stat，不再打开文件。
 */
class SymbolDirIndex(private val cacheDir: File?) {

    data class Entry(val file: File, val buildId: String?, val hasSymtab: Boolean)

    private val memory = HashMap<String, CachedEntry>()
    private var loaded = false
    private var dirty = false

    private data class CachedEntry(val size: Long, val mtime: Long, val buildId: String, val hasSymtab: Boolean)

    /**
     * 返回 Build ID 为 [buildId]、带 .symtab 的文件，新的在前。[cancelled] 返回 true 时尽快停下。
     */
    @Synchronized
    fun find(roots: List<File>, buildId: String, maxDepth: Int = 12, cancelled: () -> Boolean = { false }): List<File> {
        val id = buildId.lowercase()
        val result = LinkedHashSet<File>()
        // 1. .build-id 布局：按路径直接取
        for (root in roots) {
            for (base in listOf(File(root, ".build-id"), root)) {
                val f = File(base, "${id.take(2)}/${id.drop(2)}.debug")
                if (f.isFile) entry(f)?.takeIf { it.buildId == id && it.hasSymtab }?.let { result += f }
            }
        }
        if (result.isNotEmpty()) return result.toList()
        // 2. 递归扫描
        for (root in roots) {
            if (!root.isDirectory) continue
            val walk = root.walkTopDown().maxDepth(maxDepth).onEnter { it.name !in SKIP_DIRS && !cancelled() }
            for (f in walk) {
                if (cancelled()) break
                if (!f.isFile || !looksLikeElf(f.name) || f.length() < 64) continue
                val e = entry(f) ?: continue
                if (e.buildId == id && e.hasSymtab) result += f
            }
        }
        save()
        return result.sortedByDescending { it.lastModified() }
    }

    /** 单个文件的 Build ID 和是否有 .symtab；不是 ELF 返回 null。 */
    @Synchronized
    fun entry(f: File): Entry? {
        load()
        val key = f.absolutePath
        val size = f.length()
        val mtime = f.lastModified()
        memory[key]?.let { c ->
            if (c.size == size && c.mtime == mtime) {
                return if (c.buildId.isEmpty() && !c.hasSymtab) null else Entry(f, c.buildId.ifEmpty { null }, c.hasSymtab)
            }
        }
        val e = try {
            ElfFile.open(f).use { elf -> Entry(f, elf.buildId(), elf.section(".symtab") != null) }
        } catch (_: Exception) {
            null
        }
        memory[key] = CachedEntry(size, mtime, e?.buildId.orEmpty(), e?.hasSymtab ?: false)
        dirty = true
        return e
    }

    private fun cacheFile() = cacheDir?.let { File(it, "symbol-dir-index.properties") }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = cacheFile() ?: return
        if (!f.isFile) return
        try {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            if (p.getProperty("#format") != FORMAT) return
            for (key in p.stringPropertyNames()) {
                if (key.startsWith("#")) continue
                val parts = p.getProperty(key).split('|')
                if (parts.size != 4) continue
                memory[key] = CachedEntry(parts[0].toLongOrNull() ?: continue, parts[1].toLongOrNull() ?: continue, parts[2], parts[3] == "1")
            }
        } catch (_: Exception) {
            // 缓存坏了就重新扫
        }
    }

    private fun save() {
        if (!dirty) return
        val f = cacheFile() ?: return
        try {
            f.parentFile?.mkdirs()
            val p = Properties()
            p["#format"] = FORMAT
            // 删掉已经不存在的文件，缓存不无限增长
            memory.entries.removeIf { !File(it.key).exists() }
            for ((k, v) in memory) p[k] = "${v.size}|${v.mtime}|${v.buildId}|${if (v.hasSymtab) 1 else 0}"
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { p.store(it, "EmmyLua symbol directory index") }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            dirty = false
        } catch (_: Exception) {
            // 缓存写不进去不影响本次查找
        }
    }

    companion object {
        private const val FORMAT = "1"
        private val SKIP_DIRS = setOf(".git", ".svn", ".hg", "node_modules", "DerivedDataCache", "Saved", "Content")

        /** 只打开可能是 ELF 的文件：.so、.so.1、.debug、.dbg、.sym、.elf。 */
        fun looksLikeElf(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".so") || n.contains(".so.") || n.endsWith(".debug") || n.endsWith(".dbg") ||
                n.endsWith(".sym") || n.endsWith(".elf")
        }
    }
}

package com.fixcode.intellij.lua.translation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executor

/**
 * 翻译过的内容（照 UEToVS 的 `TranslationCache`）：内存里一个 LRU（最多 [capacity] 条），背后是只追加的
 * JSON lines 文件（IDE 系统目录下 `emmylua/translation/cache.jsonl`，所有项目共用：同一段注释到哪都一样）。
 * 文件在第一次查的时候才读；行数超过容量两倍时按最新的条目重写。线程安全。
 *
 * 缓存键和每行的格式（`{"k":"Google|zh-CN|原文","v":"译文"}`）和 UEToVS 一样。
 */
class TranslationCache(
    /** 缓存文件；null 只放内存（单测）。 */
    private val file: Path?,
    capacity: Int = 5000,
    /** 在界面线程上查到还没读文件时，把读文件放到这里做。 */
    private val background: Executor = Executor { Thread(it, "EmmyLua translation cache").apply { isDaemon = true }.start() },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    val capacity: Int = maxOf(16, capacity)
    private val gate = Any()
    // accessOrder = true：get 也算最近用过
    private val map = LinkedHashMap<String, String>(256, 0.75f, true)
    @Volatile private var loaded = file == null
    @Volatile private var loading = false

    val count: Int get() = synchronized(gate) { map.size }

    /** @param allowLoad 界面线程上传 false：文件还没读时放后台去读，这次算没命中。 */
    fun get(key: String, allowLoad: Boolean = true): String? {
        if (!loaded) {
            if (allowLoad) ensureLoaded()
            else {
                if (!loading) {
                    loading = true
                    background.execute { ensureLoaded() }
                }
                return null
            }
        }
        synchronized(gate) { return map[key] }
    }

    fun put(key: String?, value: String?) {
        if (key == null || value == null) return
        ensureLoaded()
        synchronized(gate) {
            if (!addMemory(key, value)) return
            val f = file ?: return
            try {
                Files.createDirectories(f.parent)
                Files.writeString(f, line(key, value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (e: Exception) {
                log("translate.cache.writeFailed $f", e)
            }
        }
    }

    fun clear() {
        synchronized(gate) {
            map.clear()
            loaded = true
            val f = file ?: return
            try {
                Files.deleteIfExists(f)
                log("translate.cache.cleared $f", null)
            } catch (e: Exception) {
                log("translate.cache.clearFailed $f", e)
            }
        }
    }

    /** 加进内存 / 刷新；值没变时返回 false（不用再写文件）。 */
    private fun addMemory(key: String, value: String): Boolean {
        val old = map.put(key, value)
        while (map.size > capacity) {
            val eldest = map.keys.iterator()
            eldest.next()
            eldest.remove()
        }
        return old != value
    }

    /** 现在就读缓存文件（在后台线程调）。 */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(gate) {
            if (loaded) return
            loaded = true
            val f = file ?: return
            var lines = 0
            try {
                if (!Files.exists(f)) return
                Files.newBufferedReader(f, StandardCharsets.UTF_8).useLines { seq ->
                    for (l in seq) {
                        lines++
                        val kv = parse(l) ?: continue
                        addMemory(kv.first, kv.second)
                    }
                }
                if (lines > capacity * 2) compact(f)
                log("translate.cache.loaded entries=${map.size} lines=$lines", null)
            } catch (e: Exception) {
                log("translate.cache.loadFailed $f lines=$lines", e)
            }
        }
    }

    private fun compact(f: Path) {
        val sb = StringBuilder()
        for ((k, v) in map) sb.append(line(k, v)).append('\n') // 从旧到新
        val tmp = f.resolveSibling(f.fileName.toString() + ".tmp")
        Files.writeString(tmp, sb, StandardCharsets.UTF_8)
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
        log("translate.cache.compacted entries=${map.size}", null)
    }

    companion object {
        fun key(provider: TranslationProviderKind, target: String, text: String): String = provider.cacheName + "|" + target + "|" + text

        fun line(key: String, value: String): String = JsonObject().apply {
            addProperty("k", key)
            addProperty("v", value)
        }.toString()

        fun parse(line: String?): Pair<String, String>? {
            if (line.isNullOrBlank()) return null
            return try {
                val o = JsonParser.parseString(line) as? JsonObject ?: return null
                val k = o.get("k")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
                val v = o.get("v")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
                k to v
            } catch (e: Exception) {
                null
            }
        }
    }
}

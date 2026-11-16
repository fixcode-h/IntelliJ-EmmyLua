package com.fixcode.intellij.lua.project

import java.nio.file.Files
import java.nio.file.Path

/** 找 IntelliSense 目录的纯路径逻辑，单独放出来便于不起 IDE 测。 */
object UnrealIntelliSenseRoots {
    /** 往上最多找几层 `.uproject`：`Content/Script/子目录` 打开时也要能找到。 */
    private const val MAX_UP = 8

    fun findUnrealProjectRoot(start: Path): Path? {
        var dir: Path? = start
        var depth = 0
        while (dir != null && depth <= MAX_UP) {
            if (hasUProject(dir)) return dir
            dir = dir.parent
            depth++
        }
        return null
    }

    /**
     * UnLua 写在「插件目录/Intermediate/IntelliSense」；插件可能直接在 Plugins 下，也可能多套一层分类目录。
     * 项目自己的 Intermediate/IntelliSense 也收（有的分支改了输出位置）。
     */
    fun find(start: Path): List<Path> {
        val root = findUnrealProjectRoot(start) ?: return emptyList()
        val result = mutableListOf<Path>()
        root.resolve("Intermediate").resolve("IntelliSense").takeIf { Files.isDirectory(it) }?.let(result::add)
        val plugins = root.resolve("Plugins")
        for (plugin in childDirs(plugins)) {
            val direct = plugin.resolve("Intermediate").resolve("IntelliSense")
            if (Files.isDirectory(direct)) {
                result.add(direct)
                continue
            }
            for (nested in childDirs(plugin)) {
                val inner = nested.resolve("Intermediate").resolve("IntelliSense")
                if (Files.isDirectory(inner)) result.add(inner)
            }
        }
        return result.map { it.toAbsolutePath().normalize() }.distinct()
    }

    private fun hasUProject(dir: Path): Boolean = runCatching {
        Files.list(dir).use { files ->
            files.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".uproject", ignoreCase = true) }
        }
    }.getOrDefault(false)

    private fun childDirs(dir: Path): List<Path> = runCatching {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.toList() }
    }.getOrDefault(emptyList())
}

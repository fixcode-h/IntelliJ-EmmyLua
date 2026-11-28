package com.fixcode.intellij.lua.project

import java.nio.file.Files
import java.nio.file.Path

/**
 * 给 emmylua_ls 的 `workspace.ignoreDir`：工作区里肯定没有 Lua、文件又多的目录。
 *
 * emmylua_ls 没有磁盘缓存，每次启动都把工作区整个走一遍、读全部 .lua 再建索引（上游 `vfs/loader.rs`）。
 * 走目录时只有 `ignoreDir` 能整棵剪掉，`ignoreGlobs` 是逐个文件比对，目录照样要走完。
 * UE 项目以项目根打开时，`DerivedDataCache`、`Intermediate`、`Saved`、`Binaries` 动辄几十万个文件，比 Lua 本身多得多。
 *
 * 不能剪掉的：库目录和它们的上级（emmylua_ls 的 ignoreDir 对库目录同样生效），以及 `Intermediate` 下
 * UELink 生成的声明（`UELink/`）和 UnLua 生成的声明（`IntelliSense/`），这两个后来才出现时也不能被提前剪掉。
 * 纯路径逻辑，单测在 `WorkspaceIgnoreDirsTest`。
 */
object WorkspaceIgnoreDirs {
    /** 版本管理、IDE 自己的目录：任何项目根下都剪。 */
    private val TOOL_DIRS = listOf(".git", ".svn", ".hg", ".plastic", ".vs", ".idea", "node_modules")

    /** UE 项目和每个插件下的生成目录：整棵剪。 */
    private val UE_DIRS = listOf("Binaries", "DerivedDataCache", "Saved")

    /** `Intermediate` 下保留的子目录，其余（Build、ProjectFiles、ShaderAutogen……）剪掉。 */
    private val UE_INTERMEDIATE_KEEP = setOf("intellisense", "uelink")

    /**
     * @param root 工作区根（emmylua_ls 的 workspace folder）
     * @param keep 库目录：它们自己和上级都不剪
     * @param unreal 设置里开了「识别 UE 项目」才剪 UE 的生成目录
     * @return 相对 [root] 的路径，`/` 分隔（emmylua_ls 按工作区根拼成绝对路径，和它走目录时用的根一致）
     */
    fun find(root: Path, keep: Collection<Path>, unreal: Boolean = true): List<String> {
        val base = root.toAbsolutePath().normalize()
        val kept = keep.map { it.toAbsolutePath().normalize() }
        val candidates = mutableListOf<Path>()
        TOOL_DIRS.forEach { candidates.add(base.resolve(it)) }
        val ue = if (!unreal) null else UnrealIntelliSenseRoots.findUnrealProjectRoot(base)?.toAbsolutePath()?.normalize()
        if (ue != null) {
            TOOL_DIRS.forEach { candidates.add(ue.resolve(it)) }
            for (dir in listOf(ue) + UnrealIntelliSenseRoots.pluginDirs(ue.resolve("Plugins"))) {
                UE_DIRS.forEach { candidates.add(dir.resolve(it)) }
                childDirs(dir.resolve("Intermediate"))
                    .filterNot { it.fileName.toString().lowercase() in UE_INTERMEDIATE_KEEP }
                    .forEach(candidates::add)
            }
        }
        return candidates.asSequence()
            .map { it.normalize() }
            .filter { it != base && it.startsWith(base) && Files.isDirectory(it) }
            .filterNot { dir -> kept.any { it.startsWith(dir) } }
            .map { base.relativize(it).joinToString("/") }
            .distinct()
            .sorted()
            .toList()
    }

    private fun childDirs(dir: Path): List<Path> = runCatching {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.toList() }
    }.getOrDefault(emptyList())
}

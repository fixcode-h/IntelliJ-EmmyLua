package com.tang.intellij.lua.debugger.mcp.install

import java.nio.file.Files
import java.nio.file.Path

/**
 * 从一个目录往上找版本管理根目录，规则和 UEToVS（src/UEToVS.Host/Logic/Mcp/VcsRoot.cs）一致：
 * Git（.git 目录或文件：worktree、子模块）、Mercurial（.hg）、Subversion（.svn，取连续带 .svn 的最外层，
 * 兼容 1.7 以前每层都有 .svn 的工作副本）、Plastic SCM / Unity Version Control（.plastic）、
 * Perforce（P4CONFIG 文件：环境变量 P4CONFIG 指定的名字，否则 .p4config / p4config.txt / .p4config.txt）。
 *
 * 没有 P4CONFIG 的 Perforce 工作区没有标记文件，IDE 侧可以再问 `p4 info` 的 client root（[p4ClientRoot]）。
 */
object VcsRoot {
    private val DEFAULT_P4_CONFIG_NAMES = listOf(".p4config", "p4config.txt", ".p4config.txt")

    fun find(startDir: Path, p4ConfigName: String? = System.getenv("P4CONFIG")): Path? {
        val p4Names = buildList {
            p4ConfigName?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c == '/' || c == '\\' } }?.let { add(it) }
            DEFAULT_P4_CONFIG_NAMES.forEach { if (it !in this) add(it) }
        }
        var d: Path? = startDir.toAbsolutePath().normalize()
        while (d != null) {
            if (Files.exists(d.resolve(".svn"))) {
                var top: Path = d
                var p = d.parent
                while (p != null && Files.exists(p.resolve(".svn"))) {
                    top = p
                    p = p.parent
                }
                return top
            }
            if (Files.exists(d.resolve(".git")) || Files.exists(d.resolve(".hg")) || Files.exists(d.resolve(".plastic"))) return d
            if (p4Names.any { Files.isRegularFile(d!!.resolve(it)) }) return d
            d = d.parent
        }
        return null
    }

    /**
     * 安装位置：项目目录往上找到的版本管理根；找不到时用 [p4ClientRoot]（包含项目目录才用）；都没有就用项目目录本身。
     */
    fun installRoot(projectDir: Path, p4ClientRoot: (() -> Path?)? = null): Path {
        find(projectDir)?.let { return it }
        val p4 = runCatching { p4ClientRoot?.invoke() }.getOrNull()
        if (p4 != null && isSameOrUnder(projectDir, p4)) return p4.toAbsolutePath().normalize()
        return projectDir.toAbsolutePath().normalize()
    }

    fun isSameOrUnder(dir: Path, ancestor: Path): Boolean {
        val a = ancestor.toAbsolutePath().normalize()
        val d = dir.toAbsolutePath().normalize()
        return d.startsWith(a) || d.toString().lowercase().startsWith(a.toString().lowercase().trimEnd('/', '\\') + d.fileSystem.separator) ||
            d.toString().equals(a.toString(), ignoreCase = true)
    }
}

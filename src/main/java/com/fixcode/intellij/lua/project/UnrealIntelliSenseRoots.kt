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

    /** UELink（UEKit）后台生成的声明目录；有清单才算生成过。 */
    const val UELINK_DECL_DIR = "Intermediate/UELink/LuaDecl"
    const val UELINK_DECL_MANIFEST = ".uelink-luadecl.json"

    fun uelinkDeclDir(ueRoot: Path): Path? =
        ueRoot.resolve(UELINK_DECL_DIR).takeIf { Files.isRegularFile(it.resolve(UELINK_DECL_MANIFEST)) }
            ?.toAbsolutePath()?.normalize()

    /**
     * UE 项目里要给 emmylua_ls 当库的目录：
     * - 声明：有 UELink 生成的声明目录就只用它（里面已经带了 UnLua 的静态声明），不再加 UnLua 生成的
     *   `Intermediate/IntelliSense` 和自带的 `Content/IntelliSense`：两份声明同名类型会重复定义，跳转出现两个目标。
     *   没有时收 UnLua 生成的（插件目录或项目的 `Intermediate/IntelliSense`）和 UnLua 自带的 `Content/IntelliSense`。
     * - UnLua 插件自带的 Lua 脚本 `Content/Script`（`UnLua.Class`、`Class.lua` 这些的实现）总是加上。
     * 插件可能直接在 Plugins 下，也可能多套一层分类目录。
     */
    fun find(start: Path): List<Path> {
        val root = findUnrealProjectRoot(start) ?: return emptyList()
        val plugins = pluginDirs(root.resolve("Plugins"))
        val unlua = plugins.filter(::isUnLuaPlugin)
        val result = mutableListOf<Path>()
        val decl = uelinkDeclDir(root)
        if (decl != null) {
            result.add(decl)
        } else {
            root.resolve("Intermediate").resolve("IntelliSense").takeIf { Files.isDirectory(it) }?.let(result::add)
            for (plugin in plugins) {
                plugin.resolve("Intermediate").resolve("IntelliSense").takeIf { Files.isDirectory(it) }?.let(result::add)
            }
            for (plugin in unlua) {
                plugin.resolve("Content").resolve("IntelliSense").takeIf { Files.isDirectory(it) }?.let(result::add)
            }
        }
        for (plugin in unlua) {
            plugin.resolve("Content").resolve("Script").takeIf { Files.isDirectory(it) }?.let(result::add)
        }
        return result.map { it.toAbsolutePath().normalize() }.distinct()
    }

    /** Plugins 下的插件目录：直接含 `.uplugin` 的算一个；不含的当分类目录，再往下看一层。 */
    private fun pluginDirs(pluginsRoot: Path): List<Path> = childDirs(pluginsRoot).flatMap { dir ->
        if (hasFile(dir) { it.endsWith(".uplugin", ignoreCase = true) } || Files.isDirectory(dir.resolve("Intermediate"))) listOf(dir)
        else childDirs(dir)
    }

    /** UnLua 或它的二次开发分支：`.uplugin` 名字以 UnLua 开头。 */
    private fun isUnLuaPlugin(dir: Path): Boolean =
        hasFile(dir) { it.startsWith("unlua", ignoreCase = true) && it.endsWith(".uplugin", ignoreCase = true) }

    private fun hasFile(dir: Path, match: (String) -> Boolean): Boolean = runCatching {
        Files.list(dir).use { files -> files.anyMatch { Files.isRegularFile(it) && match(it.fileName.toString()) } }
    }.getOrDefault(false)

    private fun hasUProject(dir: Path): Boolean = hasFile(dir) { it.endsWith(".uproject", ignoreCase = true) }

    private fun childDirs(dir: Path): List<Path> = runCatching {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.toList() }
    }.getOrDefault(emptyList())
}

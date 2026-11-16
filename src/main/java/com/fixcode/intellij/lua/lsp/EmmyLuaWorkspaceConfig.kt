package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.project.LuaProjectSettings
import com.fixcode.intellij.lua.project.LuaSettings
import com.fixcode.intellij.lua.project.UnrealIntelliSenseRoots
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * 插件给 emmylua_ls 的「部分配置」，通过 `workspace/configuration`（section `emmylua`）下发。
 * emmylua_ls 把它合并在工作区 `.emmyrc.json` 之后：对象逐项合并、数组取并集，所以库目录是叠加的。
 */
object EmmyLuaWorkspaceConfig {
    const val SECTION = "emmylua"
    private val USER_CONFIG_FILES = listOf(".emmyrc.json", ".luarc.json", ".emmyrc.lua")

    fun build(project: Project): JsonObject {
        val root = JsonObject()
        val libraries = libraries(project)
        if (libraries.isNotEmpty()) {
            root.add("workspace", JsonObject().apply {
                add("library", JsonArray().apply { libraries.forEach { add(it.toString()) } })
            })
        }
        // 用户写了配置文件就以文件为准：部分配置在文件之后合并，标量会覆盖文件里的值
        if (findUserConfig(project) == null) {
            root.add("runtime", JsonObject().apply {
                addProperty("version", LuaSettings.instance.languageLevel.emmyrcName)
            })
        }
        return root
    }

    fun projectRoot(project: Project): Path? =
        project.basePath?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }

    fun findUserConfig(project: Project): Path? {
        val root = projectRoot(project) ?: return null
        return USER_CONFIG_FILES.map { root.resolve(it) }.firstOrNull { Files.isRegularFile(it) }
    }

    /** 设置里的额外库目录 + 自动探测到的 UE IntelliSense 目录；工作区里面的不重复加。 */
    fun libraries(project: Project): List<Path> {
        val root = projectRoot(project)
        val settings = LuaProjectSettings.getInstance(project)
        val result = LinkedHashSet<Path>()
        for (raw in settings.additionalSourcesRoot) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val p = runCatching { Path.of(text) }.getOrNull() ?: continue
            val abs = (if (p.isAbsolute || root == null) p else root.resolve(p)).normalize()
            if (Files.isDirectory(abs)) result.add(abs)
        }
        if (settings.detectUnrealProject) result.addAll(unrealLibraries(project))
        return result.filterNot { root != null && it.startsWith(root) }
    }

    fun unrealLibraries(project: Project): List<Path> {
        val root = projectRoot(project) ?: return emptyList()
        return UnrealIntelliSenseRoots.find(root)
    }
}

package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.project.LuaProjectSettings
import com.fixcode.intellij.lua.project.LuaSettings
import com.fixcode.intellij.lua.project.UnrealIntelliSenseRoots
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
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
        val disabled = defaultDisabledDiagnostics(findUserConfig(project))
        if (disabled.isNotEmpty()) {
            root.add("diagnostics", JsonObject().apply {
                add("disable", JsonArray().apply { disabled.forEach { add(it) } })
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

    /**
     * 默认关掉的诊断。emmylua_ls 0.25.1 把字段第一次赋的字面量当成字段类型（`self.bFlag = false` → `false`，
     * `self.Timer = nil` → `nil`），后面赋别的值也不放宽，于是 `if self.bFlag then` 报「永远为假」。
     * 面向对象写法里字段几乎都这样初始化，这条诊断误报远多于有用的提示，所以默认关。
     * `disable` 优先于 `enables`，而数组和用户配置取并集：用户在配置文件的 `diagnostics.enables` 里写了的就不下发，
     * 留给用户自己打开。上游主干（未发版）已修好 true/false，但 `self.x = nil` 初始化的字段仍报；升级 emmylua_ls 后两种都不报了再去掉。
     */
    internal val DEFAULT_DISABLED_DIAGNOSTICS = listOf("unnecessary-if")

    internal fun defaultDisabledDiagnostics(userConfig: Path?): List<String> {
        val enabled = userConfig?.let { readEnabledDiagnostics(it) }.orEmpty()
        return DEFAULT_DISABLED_DIAGNOSTICS.filterNot { it in enabled }
    }

    /** 读用户配置里的 `diagnostics.enables`；`.emmyrc.lua` 或读不出来时当没写。允许注释和尾逗号。 */
    @Suppress("DEPRECATION") // gson 2.11 的 setLenient 已标弃用，换成 Strictness 要求 IDE 里的 gson 也是新版
    internal fun readEnabledDiagnostics(file: Path): Set<String> {
        if (!file.fileName.toString().endsWith(".json")) return emptySet()
        return runCatching {
            Files.newBufferedReader(file).use { reader ->
                val json = JsonReader(reader).apply { isLenient = true }
                val enables = JsonParser.parseReader(json).asJsonObject
                    .getAsJsonObject("diagnostics")?.getAsJsonArray("enables") ?: return@use emptySet<String>()
                enables.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.toSet()
            }
        }.getOrDefault(emptySet())
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

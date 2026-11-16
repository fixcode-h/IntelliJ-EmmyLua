/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.tang.intellij.lua.project

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.tang.intellij.lua.lang.LuaIcons
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/**
 * UE 项目里 UnLua「Generate IntelliSense」生成的声明文件（蓝图类型、`UE.xxx`）当作库加进索引。
 *
 * 为什么要这个：UE 项目常常只在 IDE 里打开 `Content/Script`，声明文件在 `Plugins/UnLua/Intermediate/IntelliSense`，
 * 在项目外面不会被索引，于是 `---@type BP_xxx_C` 报未解析类型、继承来的成员 Ctrl+点击跳不过去。
 * 这里往上找含 `.uproject` 的目录，把找到的 IntelliSense 目录加成库；已经在项目里的目录不重复加。
 */
class UnrealIntelliSenseLibraryProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val base = project.basePath ?: return emptyList()
        val basePath = runCatching { Path.of(base).toAbsolutePath().normalize() }.getOrNull() ?: return emptyList()
        val fs = LocalFileSystem.getInstance()
        return UnrealIntelliSenseRoots.find(basePath)
            .filterNot { it.startsWith(basePath) }
            .mapNotNull { dir -> fs.findFileByNioFile(dir)?.takeIf { it.isDirectory } }
            .map { IntelliSenseLibrary(it) }
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        getAdditionalProjectLibraries(project).flatMap { it.sourceRoots }

    class IntelliSenseLibrary(private val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {
        private val roots = listOf(root)
        override fun getSourceRoots(): Collection<VirtualFile> = roots
        override fun hashCode() = root.hashCode()
        override fun equals(other: Any?) = other is IntelliSenseLibrary && other.root == root
        override fun getPresentableText() = "UE IntelliSense"
        override fun getLocationString(): String = root.presentableUrl
        override fun getIcon(unused: Boolean): Icon = LuaIcons.FILE
    }
}

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

/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.tang.intellij.lua.debugger.emmy.attach

import com.tang.intellij.lua.debugger.resources.DebuggerResourceService
import java.io.File

object DebuggerPathUtils {
    fun getEmmyDebuggerPath(): String? =
        runCatching { DebuggerResourceService.emmyNativeToolsDirectory().toString() }.getOrNull()

    fun getEmmyToolPath(arch: WinArch): String? = findArchFile(arch, "emmy_tool.exe")

    fun getEmmyHookPath(arch: WinArch): String? = findArchFile(arch, "emmy_hook.dll")

    fun validateDebuggerTools(): String? {
        val root = getEmmyDebuggerPath() ?: return "无法提取 Emmy 调试器工具"
        val missing = WinArch.entries.flatMap { arch ->
            listOf("emmy_tool.exe", "emmy_hook.dll", "emmy_core.dll", "EasyHook.dll")
                .filterNot { File(root, "${archDirectory(arch)}/$it").isFile }
                .map { "${archDirectory(arch)}/$it" }
        }
        return missing.takeIf { it.isNotEmpty() }?.joinToString(
            prefix = "Emmy 调试器资源不完整: ",
            separator = ", "
        )
    }

    private fun findArchFile(arch: WinArch, fileName: String): String? {
        val root = getEmmyDebuggerPath() ?: return null
        return File(root, "${archDirectory(arch)}/$fileName").takeIf(File::isFile)?.absolutePath
    }

    private fun archDirectory(arch: WinArch): String = when (arch) {
        WinArch.X86 -> "x86"
        WinArch.X64 -> "x64"
    }
}

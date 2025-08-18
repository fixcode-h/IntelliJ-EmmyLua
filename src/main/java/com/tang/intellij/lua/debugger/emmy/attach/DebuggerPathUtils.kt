/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.debugger.emmy.attach

import com.intellij.openapi.application.PathManager
import com.tang.intellij.lua.psi.LuaFileUtil
import java.io.File

/**
 * 调试器路径工具类
 */
object DebuggerPathUtils {

    /**
     * 获取Emmy调试工具的根目录
     */
         fun getEmmyDebuggerPath(): String? {
         // 1. 首先尝试从资源目录获取（开发和生产环境）
         val resourcePath = LuaFileUtil.getPluginVirtualFile("debugger/emmy/windows")
         if (resourcePath != null) {
             val resourceDir = File(resourcePath)
             if (resourceDir.exists()) {
                 return resourcePath
             }
         }

         // 2. 尝试从插件安装目录获取（生产环境）
         try {
             val pluginPath = PathManager.getPluginsPath()
             val emmyLuaPluginDir = File(pluginPath, "EmmyLua")
             if (emmyLuaPluginDir.exists()) {
                 val debuggerPath = File(emmyLuaPluginDir, "lib/debugger/emmy/windows")
                 if (debuggerPath.exists()) {
                     return debuggerPath.absolutePath
                 }
             }
         } catch (e: Exception) {
             // 忽略异常，继续尝试其他路径
         }

         // 3. 尝试从类路径获取（JAR包内）
         try {
             val classLoader = DebuggerPathUtils::class.java.classLoader
             val resource = classLoader.getResource("debugger/emmy/windows")
             if (resource != null) {
                 val uri = resource.toURI()
                 // 如果是jar包内的资源，需要提取到临时目录
                 if (uri.scheme == "jar") {
                     return extractFromJar("debugger/emmy/windows")
                 } else {
                     return File(uri).absolutePath
                 }
             }
         } catch (e: Exception) {
             // 忽略异常
         }

         // 4. 最后尝试相对路径（开发环境）
         val devPath = File("src/main/resources/debugger/emmy/windows")
         if (devPath.exists()) {
             return devPath.absolutePath
         }

         return null
     }

     /**
      * 从JAR包中提取调试工具到临时目录
      */
     private fun extractFromJar(resourcePath: String): String? {
         try {
             val tempDir = File(System.getProperty("java.io.tmpdir"), "emmylua-debugger")
             tempDir.mkdirs()
             
             val classLoader = DebuggerPathUtils::class.java.classLoader
             val resourceUrl = classLoader.getResource(resourcePath) ?: return null
             
             // 创建目标目录
             val targetDir = File(tempDir, "windows")
             targetDir.mkdirs()
             
             // 提取各个架构的文件
             extractArchFiles(classLoader, "debugger/emmy/windows/x86", File(targetDir, "x86"))
             extractArchFiles(classLoader, "debugger/emmy/windows/x64", File(targetDir, "x64"))
             
             return targetDir.absolutePath
         } catch (e: Exception) {
             return null
         }
     }
     
     /**
      * 提取指定架构的文件
      */
     private fun extractArchFiles(classLoader: ClassLoader, resourcePath: String, targetDir: File) {
         try {
             targetDir.mkdirs()
             val files = listOf("emmy_tool.exe", "emmy_hook.dll", "emmy_core.dll", "EasyHook.dll")
             
             for (fileName in files) {
                 val resourceStream = classLoader.getResourceAsStream("$resourcePath/$fileName")
                 if (resourceStream != null) {
                     val targetFile = File(targetDir, fileName)
                     resourceStream.use { input ->
                         targetFile.outputStream().use { output ->
                             input.copyTo(output)
                         }
                     }
                     // 设置可执行权限（对于exe文件）
                     if (fileName.endsWith(".exe")) {
                         targetFile.setExecutable(true)
                     }
                 }
             }
         } catch (e: Exception) {
             // 忽略提取错误
         }
     }

    /**
     * 获取指定架构的Emmy工具路径
     */
    fun getEmmyToolPath(arch: WinArch): String? {
        val debuggerPath = getEmmyDebuggerPath() ?: return null
        val archName = when (arch) {
            WinArch.X64 -> "x64"
            WinArch.X86 -> "x86"
        }
        val toolPath = File(debuggerPath, "$archName/emmy_tool.exe")
        return if (toolPath.exists()) toolPath.absolutePath else null
    }

    /**
     * 获取指定架构的Emmy Hook DLL路径
     */
    fun getEmmyHookPath(arch: WinArch): String? {
        val debuggerPath = getEmmyDebuggerPath() ?: return null
        val archName = when (arch) {
            WinArch.X64 -> "x64"
            WinArch.X86 -> "x86"
        }
        val dllPath = File(debuggerPath, "$archName/emmy_hook.dll")
        return if (dllPath.exists()) dllPath.absolutePath else null
    }

    /**
     * 验证调试器工具是否可用
     */
    fun validateDebuggerTools(): String? {
        val debuggerPath = getEmmyDebuggerPath()
        if (debuggerPath == null) {
            return "找不到Emmy调试器工具目录"
        }

        val x86Tool = getEmmyToolPath(WinArch.X86)
        val x64Tool = getEmmyToolPath(WinArch.X64)

        if (x86Tool == null && x64Tool == null) {
            return "找不到Emmy调试工具(emmy_tool.exe)"
        }

        val x86Hook = getEmmyHookPath(WinArch.X86)
        val x64Hook = getEmmyHookPath(WinArch.X64)

        if (x86Hook == null && x64Hook == null) {
            return "找不到Emmy调试库(emmy_hook.dll)"
        }

        return null // 验证通过
    }
} 
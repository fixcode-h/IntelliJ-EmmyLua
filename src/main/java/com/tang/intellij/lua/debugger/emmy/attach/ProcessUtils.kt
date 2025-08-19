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

import java.io.File

/**
 * 模块分析结果
 */
data class ProcessModuleAnalysis(
    val hasLuaRuntime: Boolean,
    val luaModules: List<String>,
    val allModules: List<String>,
    val errorMessage: String? = null
)

/**
 * 分析进程模块，检测是否包含Lua运行时
 * 由于emmy_tool.exe没有list_modules命令，我们使用Windows API来检测模块
 */
fun ProcessUtils.analyzeProcessModules(pid: Int, arch: WinArch): ProcessModuleAnalysis {
    return try {
        // 使用PowerShell来获取进程模块列表
        val commands = listOf(
            "powershell.exe",
            "-Command",
            "Get-Process -Id $pid | Select-Object -ExpandProperty Modules | Select-Object ModuleName"
        )
        
        val processBuilder = ProcessBuilder(commands)
        val process = processBuilder.start()
        
        val output = process.inputStream.bufferedReader().use { reader ->
            reader.readText()
        }
        
        val errorOutput = process.errorStream.bufferedReader().use { reader ->
            reader.readText()
        }
        
        val exitCode = process.waitFor()
        
        if (exitCode != 0) {
            // 如果PowerShell失败，尝试使用tasklist命令
            return analyzeModulesWithTasklist(pid)
        }
        
        // 解析PowerShell输出
        val modules = output.lines()
            .filter { line -> 
                line.isNotBlank() && 
                !line.startsWith("ModuleName") && 
                !line.startsWith("----------") &&
                line.trim().isNotEmpty()
            }
            .map { it.trim() }
        
        // 检测标准Lua运行时模块（更严格的检测逻辑）
        val luaModules = modules.filter { module ->
            isStandardLuaRuntime(module)
        }
        
        ProcessModuleAnalysis(
            hasLuaRuntime = luaModules.isNotEmpty(),
            luaModules = luaModules,
            allModules = modules.take(50) // 限制显示数量
        )
        
    } catch (e: Exception) {
        ProcessModuleAnalysis(
            hasLuaRuntime = false,
            luaModules = emptyList(),
            allModules = emptyList(),
            errorMessage = "模块分析异常: ${e.message}"
        )
    }
}

/**
 * 使用tasklist命令作为备用方案分析模块
 */
private fun analyzeModulesWithTasklist(pid: Int): ProcessModuleAnalysis {
    return try {
        val commands = listOf(
            "tasklist",
            "/M", 
            "/FI",
            "PID eq $pid"
        )
        
        val processBuilder = ProcessBuilder(commands)
        val process = processBuilder.start()
        
        val output = process.inputStream.bufferedReader().use { reader ->
            reader.readText()
        }
        
        val exitCode = process.waitFor()
        
        if (exitCode != 0) {
            return ProcessModuleAnalysis(
                hasLuaRuntime = false,
                luaModules = emptyList(),
                allModules = emptyList(),
                errorMessage = "无法获取进程模块信息，请确保目标进程存在且有足够权限"
            )
        }
        
        // 解析tasklist输出
        val modules = output.lines()
            .filter { line -> 
                line.trim().isNotEmpty() && 
                !line.contains("==========") &&
                !line.contains("映像名称") &&
                !line.contains("Image Name") &&
                line.contains(".dll") || line.contains(".exe")
            }
            .map { line ->
                // 提取模块名称
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.isNotEmpty()) parts[0] else line.trim()
            }
            .filter { it.isNotEmpty() }
        
        // 检测标准Lua运行时模块（更严格的检测逻辑）
        val luaModules = modules.filter { module ->
            isStandardLuaRuntime(module)
        }
        
        ProcessModuleAnalysis(
            hasLuaRuntime = luaModules.isNotEmpty(),
            luaModules = luaModules,
            allModules = modules.take(20) // 限制显示数量
        )
        
    } catch (e: Exception) {
        ProcessModuleAnalysis(
            hasLuaRuntime = false,
            luaModules = emptyList(),
            allModules = emptyList(),
            errorMessage = "备用模块检测失败: ${e.message}"
        )
    }
}

/**
 * 判断模块是否为标准Lua运行时
 * 只有真正的Lua运行时模块才会被认为是有效的调试目标
 */
private fun isStandardLuaRuntime(moduleName: String): Boolean {
    val lowerModule = moduleName.lowercase()
    
    // 标准Lua运行时DLL模式
    val standardLuaPatterns = listOf(
        // 标准Lua版本
        "lua.dll",
        "lua51.dll", "lua52.dll", "lua53.dll", "lua54.dll",
        "lua5.1.dll", "lua5.2.dll", "lua5.3.dll", "lua5.4.dll",
        
        // LuaJIT
        "luajit.dll", "lua51.dll", // LuaJIT通常使用lua51.dll
        
        // 其他常见的Lua运行时
        "luadll.dll",
        "lualib.dll"
    )
    
    // 检查是否匹配标准模式
    for (pattern in standardLuaPatterns) {
        if (lowerModule.endsWith(pattern)) {
            return true
        }
    }
    
    // 检查是否为完整路径中的标准Lua DLL
    if (lowerModule.contains("\\") || lowerModule.contains("/")) {
        val fileName = lowerModule.substringAfterLast("\\").substringAfterLast("/")
        for (pattern in standardLuaPatterns) {
            if (fileName == pattern) {
                return true
            }
        }
    }
    
    // 排除包含"lua"但不是标准运行时的模块
    // 例如：luacheck.dll, luaunit.dll, mylua.dll 等
    return false
}

/**
 * 检测调试端口是否可连接
 */
fun ProcessUtils.isPortConnectable(host: String = "localhost", port: Int, timeoutMs: Int = 5000): Boolean {
    return try {
        java.net.Socket().use { socket ->
            val address = when {
                host == "::1" -> {
                    // IPv6回环地址
                    java.net.InetSocketAddress(java.net.InetAddress.getByName("::1"), port)
                }
                host.startsWith("[") && host.endsWith("]") -> {
                    // IPv6地址格式 [::1]
                    val ipv6Host = host.substring(1, host.length - 1)
                    java.net.InetSocketAddress(java.net.InetAddress.getByName(ipv6Host), port)
                }
                else -> {
                    // IPv4地址或主机名
                    java.net.InetSocketAddress(host, port)
                }
            }
            socket.connect(address, timeoutMs)
            true
        }
    } catch (e: Exception) {
        false
    }
}
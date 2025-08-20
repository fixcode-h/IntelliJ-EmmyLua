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

import com.tang.intellij.lua.debugger.emmy.EmmyWinArch
import java.io.File

/**
 * 进程信息
 */
data class ProcessInfo(
    val pid: Int,
    val name: String,
    val title: String,
    val path: String
) {
    /**
     * UE进程类型（懒加载）
     */
    val ueProcessType: UEProcessType by lazy {
        UEProcessClassifier.classifyUEProcess(this)
    }
    
    /**
     * 获取显示文本
     */
    fun getDisplayText(): String {
        val icon = UEProcessClassifier.getProcessTypeIcon(ueProcessType)
        val typeText = if (ueProcessType != UEProcessType.NON_UE) {
            " [${ueProcessType.displayName}]"
        } else {
            ""
        }
        
        // 添加窗口标题信息
        val titleText = if (title.isNotEmpty() && title != name) {
            " - $title"
        } else {
            ""
        }
        
        return "$icon $pid $typeText: $titleText".trim()
    }
    
    /**
     * 获取详细信息
     */
    fun getDetailText(): String = path
    
    /**
     * 获取描述信息（包含进程类型描述）
     */
    fun getDescriptionText(): String {
        return if (ueProcessType != UEProcessType.NON_UE) {
            "${ueProcessType.description}: $title"
        } else {
            title
        }
    }

    /**
     * 判断是否为虚幻引擎进程
     */
    fun isUnrealEngineProcess(ueProcessNames: List<String>): Boolean {
        val lowerName = name.lowercase()
        return ueProcessNames.any { ueName -> lowerName.contains(ueName.lowercase()) }
    }

    /**
     * 检查是否在黑名单中
     */
    fun isInBlacklist(blacklist: List<String>): Boolean {
        return blacklist.any { blacklistItem ->
            name.contains(blacklistItem, ignoreCase = true) ||
            title.contains(blacklistItem, ignoreCase = true) ||
            path.contains(blacklistItem, ignoreCase = true)
        }
    }

    /**
     * 检查是否匹配进程名称
     */
    fun matchesProcessName(processName: String): Boolean {
        if (processName.isEmpty()) return true
        return title.contains(processName, ignoreCase = true) || 
               name.contains(processName, ignoreCase = true)
    }
}

/**
 * Windows架构枚举
 */
enum class WinArch {
    X86, X64
}

/**
 * 将WinArch转换为EmmyWinArch
 */
fun WinArch.toEmmyWinArch(): EmmyWinArch {
    return when (this) {
        WinArch.X64 -> EmmyWinArch.X64
        WinArch.X86 -> EmmyWinArch.X86
    }
}

/**
 * 将EmmyWinArch转换为WinArch
 */
fun EmmyWinArch.toWinArch(): WinArch {
    return when (this) {
        EmmyWinArch.X64 -> WinArch.X64
        EmmyWinArch.X86 -> WinArch.X86
    }
}

/**
 * 进程工具类
 */
object ProcessUtils {
    /**
     * 根据PID计算调试端口
     */
    fun getPortFromPid(pid: Int): Int {
        var port = pid
        while (port > 0xffff) { 
            port -= 0xffff 
        }
        while (port < 0x400) { 
            port += 0x400 
        }
        return port
    }

    /**
     * 检测进程架构
     */
    fun detectProcessArch(pid: Int): WinArch {
        return try {
            val toolPath = DebuggerPathUtils.getEmmyToolPath(WinArch.X86)
                ?: return WinArch.X86 // 默认返回X86
            
            val processBuilder = ProcessBuilder(
                toolPath,
                "arch_pid",
                pid.toString()
            )
            processBuilder.directory(File(toolPath).parentFile)
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            if (exitCode == 0) WinArch.X64 else WinArch.X86
        } catch (e: Exception) {
            WinArch.X86 // 默认返回X86
        }
    }


}
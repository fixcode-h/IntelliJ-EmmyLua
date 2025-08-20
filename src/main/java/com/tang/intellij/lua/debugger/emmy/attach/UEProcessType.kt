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

/**
 * UE进程类型枚举
 */
enum class UEProcessType(val displayName: String, val description: String) {
    EDITOR("编辑器", "UE编辑器进程"),
    DSSERVER("专用服务器", "UE专用服务器进程"),
    GAME("独立游戏", "UE独立游戏进程"),
    BUILD_TOOL("构建工具", "UE构建相关工具"),
    OTHER("其他", "其他UE相关进程"),
    NON_UE("非UE进程", "非虚幻引擎进程")
}

/**
 * UE进程分类器
 */
object UEProcessClassifier {
    
    /**
     * 编辑器进程名称模式
     */
    private val editorPatterns = listOf(
        "UE4Editor", "UE5Editor", "UnrealEditor", "UnrealEngine"
    )
    
    /**
     * 专用服务器进程名称模式
     */
    private val dsServerPatterns = listOf(
        "Server", "DedicatedServer", "DS"
    )
    
    /**
     * 构建工具进程名称模式
     */
    private val buildToolPatterns = listOf(
        "UnrealHeaderTool", "UnrealBuildTool", "UnrealLightmass", 
        "ShaderCompileWorker", "CrashReportClient", "UnrealCEFSubProcess"
    )
    
    /**
     * 游戏进程排除模式（这些不是游戏进程）
     */
    private val gameExcludePatterns = listOf(
        "Editor", "Tool", "Worker", "Client", "Helper", "Crash", "CEF"
    )
    
    /**
     * 分类UE进程类型
     */
    fun classifyUEProcess(processInfo: ProcessInfo): UEProcessType {
        val name = processInfo.name
        val title = processInfo.title
        val path = processInfo.path

        // 检查是否为专用服务器进程
        if (isDSServerProcess(name, title, path)) {
            return UEProcessType.DSSERVER
        }

        // 检查是否为编辑器进程
        if (isEditorProcess(name, title, path)) {
            return UEProcessType.EDITOR
        }

        // 检查是否为构建工具进程
        if (isBuildToolProcess(name, title, path)) {
            return UEProcessType.BUILD_TOOL
        }
        
        // 检查是否为游戏进程
        if (isGameProcess(name, title, path)) {
            return UEProcessType.GAME
        }
        
        // 检查是否为UE相关进程
        if (isUERelatedProcess(name, title, path)) {
            return UEProcessType.OTHER
        }
        
        return UEProcessType.NON_UE
    }
    
    /**
     * 检查是否为编辑器进程
     */
    private fun isEditorProcess(name: String, title: String, path: String): Boolean {
        return editorPatterns.any { pattern ->
            name.contains(pattern, ignoreCase = true) ||
            title.contains(pattern, ignoreCase = true) ||
            path.contains(pattern, ignoreCase = true)
        }
    }
    
    /**
     * 检查是否为专用服务器进程
     */
    private fun isDSServerProcess(name: String, title: String, path: String): Boolean {
        // 首先确保是UE相关进程
        if (!isUERelatedProcess(name, title, path)) {
            return false
        }
        
        // 检查是否包含服务器相关关键词
        val hasServerKeyword = dsServerPatterns.any { pattern ->
            name.contains(pattern, ignoreCase = true) ||
            title.contains(pattern, ignoreCase = true) ||
            path.contains(pattern, ignoreCase = true)
        }
        
        // 检查窗口标题中是否包含UE编辑器exe文件名（DS服务器特征）
        // 使用正则表达式匹配 UE4Editor{任意字符}.exe 模式
        val ueEditorPattern = Regex("UE[45]?Editor.*\.exe", RegexOption.IGNORE_CASE)
        val isUEEditorInTitle = ueEditorPattern.containsMatchIn(title)
        
        return hasServerKeyword || isUEEditorInTitle
    }
    
    /**
     * 检查是否为构建工具进程
     */
    private fun isBuildToolProcess(name: String, title: String, path: String): Boolean {
        return buildToolPatterns.any { pattern ->
            name.contains(pattern, ignoreCase = true) ||
            title.contains(pattern, ignoreCase = true) ||
            path.contains(pattern, ignoreCase = true)
        }
    }
    
    /**
     * 检查是否为游戏进程
     */
    private fun isGameProcess(name: String, title: String, path: String): Boolean {
        // 首先检查是否为UE相关进程
        if (!isUERelatedProcess(name, title, path)) {
            return false
        }
        
        // 排除编辑器、构建工具和服务器进程
        if (isEditorProcess(name, title, path) || 
            isBuildToolProcess(name, title, path) ||
            isDSServerProcess(name, title, path)) {
            return false
        }
        
        // 排除包含特定关键词的进程
        val hasExcludeKeyword = gameExcludePatterns.any { pattern ->
            name.contains(pattern, ignoreCase = true) ||
            title.contains(pattern, ignoreCase = true)
        }
        
        return !hasExcludeKeyword
    }
    
    /**
     * 检查是否为UE相关进程
     */
    private fun isUERelatedProcess(name: String, title: String, path: String): Boolean {
        val ueKeywords = listOf(
            "Unreal", "UE4", "UE5", "Engine", "Epic"
        )
        
        return ueKeywords.any { keyword ->
            name.contains(keyword, ignoreCase = true) ||
            title.contains(keyword, ignoreCase = true) ||
            path.contains(keyword, ignoreCase = true)
        }
    }
    
    /**
     * 获取进程类型的显示图标
     */
    fun getProcessTypeIcon(processType: UEProcessType): String {
        return when (processType) {
            UEProcessType.EDITOR -> "🎨"
            UEProcessType.DSSERVER -> "🖥️"
            UEProcessType.GAME -> "🎮"
            UEProcessType.BUILD_TOOL -> "🔧"
            UEProcessType.OTHER -> "⚙️"
            UEProcessType.NON_UE -> "📄"
        }
    }
    
    /**
     * 获取进程类型的优先级（用于排序）
     */
    fun getProcessTypePriority(processType: UEProcessType): Int {
        return when (processType) {
            UEProcessType.EDITOR -> 1
            UEProcessType.GAME -> 2
            UEProcessType.DSSERVER -> 3
            UEProcessType.BUILD_TOOL -> 4
            UEProcessType.OTHER -> 5
            UEProcessType.NON_UE -> 6
        }
    }
}
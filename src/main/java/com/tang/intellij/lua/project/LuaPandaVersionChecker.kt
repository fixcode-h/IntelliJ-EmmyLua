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

package com.tang.intellij.lua.project

import com.intellij.execution.RunManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.tang.intellij.lua.debugger.luapanda.LuaPandaDebugConfiguration
import java.io.File
import java.io.IOException
import java.util.regex.Pattern

/**
 * LuaPanda 版本检查器
 * 在项目打开时检查 LuaPanda.lua 文件是否存在以及版本是否需要更新
 */
class LuaPandaVersionChecker : ProjectManagerListener {
    
    companion object {
        private const val LUAPANDA_FILE_NAME = "LuaPanda.lua"
        private const val NOTIFICATION_GROUP_ID = "LuaPanda Version Check"
        
        private val VERSION_PATTERN = Pattern.compile("debuggerVer\\s*=\\s*[\"']([^\"']+)[\"']")
    }
    
    @Deprecated("This method overrides a deprecated member")
    override fun projectOpened(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            checkLuaPandaVersion(project)
        }
    }
    
    private fun checkLuaPandaVersion(project: Project) {
        // 首先检查项目是否配置了 LuaPanda 调试
        if (!hasLuaPandaDebugConfiguration(project)) {
            return // 如果没有配置 LuaPanda 调试，则不进行检查
        }
        
        @Suppress("DEPRECATION")
        val projectRoot = project.baseDir ?: return
        val luaPandaFile = projectRoot.findChild(LUAPANDA_FILE_NAME)
        
        if (luaPandaFile == null) {
            // LuaPanda.lua 文件不存在，询问用户是否添加
            showAddLuaPandaConfirmation(project)
        } else {
            // 检查版本号
            val currentVersion = extractVersionFromFile(luaPandaFile)
            val pluginVersion = getPluginLuaPandaVersion()
            if (currentVersion == null || isVersionOutdated(currentVersion, pluginVersion)) {
                showUpdateLuaPandaNotification(project, currentVersion)
            }
        }
    }
    
    /**
     * 检查项目是否配置了 LuaPanda 调试
     */
    private fun hasLuaPandaDebugConfiguration(project: Project): Boolean {
        val runManager = RunManager.getInstance(project)
        return runManager.allConfigurationsList.any { it is LuaPandaDebugConfiguration }
    }
    
    private fun extractVersionFromFile(file: VirtualFile): String? {
        try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val matcher = VERSION_PATTERN.matcher(content)
            if (matcher.find()) {
                return matcher.group(1)
            }
        } catch (e: IOException) {
            // 读取文件失败，忽略
        }
        return null
    }
    
    private fun extractVersionFromLuaPanda(content: String): String {
        val versionPattern = Pattern.compile("local\\s+debuggerVer\\s*=\\s*[\"']([^\"']+)[\"']")
        val matcher = versionPattern.matcher(content)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            "unknown"
        }
    }
    
    private fun getPluginLuaPandaVersion(): String {
        return try {
            val content = String(getLuaPandaResourceContent())
            extractVersionFromLuaPanda(content)
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun isVersionOutdated(currentVersion: String, pluginVersion: String): Boolean {
        return compareVersions(currentVersion, pluginVersion) < 0
    }
    
    private fun compareVersions(version1: String, version2: String): Int {
        val parts1 = version1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = version2.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLength = maxOf(parts1.size, parts2.size)
        
        for (i in 0 until maxLength) {
            val part1 = parts1.getOrNull(i) ?: 0
            val part2 = parts2.getOrNull(i) ?: 0
            
            when {
                part1 < part2 -> return -1
                part1 > part2 -> return 1
            }
        }
        
        return 0
    }
    
    private fun showAddLuaPandaConfirmation(project: Project) {
        val notification = Notification(
            NOTIFICATION_GROUP_ID,
            "LuaPanda.lua 文件缺失",
            "项目中未找到 LuaPanda.lua 文件，这可能影响调试功能。",
            NotificationType.WARNING
        )
        
        notification.addAction(object : NotificationAction("添加 LuaPanda.lua 文件") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                copyLuaPandaToProject(project)
                notification.expire()
            }
        })
        
        notification.addAction(object : NotificationAction("忽略") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                notification.expire()
            }
        })
        
        Notifications.Bus.notify(notification, project)
    }
    
    private fun showUpdateLuaPandaNotification(project: Project, currentVersion: String?) {
        val pluginVersion = getPluginLuaPandaVersion()
        val versionText = if (currentVersion != null) "当前版本: $currentVersion" else "版本未知"
        val notification = Notification(
            NOTIFICATION_GROUP_ID,
            "LuaPanda 调试器需要更新",
            "检测到 LuaPanda.lua 文件版本过旧。$versionText，建议版本: $pluginVersion",
            NotificationType.WARNING
        )
        
        notification.addAction(object : NotificationAction("更新 LuaPanda.lua") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                copyLuaPandaToProject(project)
                notification.expire()
            }
        })
        
        notification.addAction(object : NotificationAction("忽略") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                notification.expire()
            }
        })
        
        Notifications.Bus.notify(notification, project)
    }
    
    private fun copyLuaPandaToProject(project: Project) {
        ApplicationManager.getApplication().runWriteAction {
            try {
                @Suppress("DEPRECATION")
                val projectRoot = project.baseDir ?: return@runWriteAction
                
                // 从插件项目根目录获取 LuaPanda.lua 文件
                val content = getLuaPandaResourceContent()
                
                // 写入到项目根目录
                val existingFile = projectRoot.findChild(LUAPANDA_FILE_NAME)
                val targetFile = if (existingFile != null) {
                    existingFile
                } else {
                    projectRoot.createChildData(this, LUAPANDA_FILE_NAME)
                }
                
                targetFile.setBinaryContent(content)
                
                // 刷新文件系统
                VirtualFileManager.getInstance().syncRefresh()
                
                val successNotification = Notification(
                    NOTIFICATION_GROUP_ID,
                    "LuaPanda.lua 更新成功",
                    "LuaPanda.lua 文件已成功添加/更新到项目根目录。",
                    NotificationType.INFORMATION
                )
                Notifications.Bus.notify(successNotification, project)
                
            } catch (e: Exception) {
                val errorNotification = Notification(
                    NOTIFICATION_GROUP_ID,
                    "LuaPanda.lua 更新失败",
                    "更新 LuaPanda.lua 文件时发生错误: ${e.message}",
                    NotificationType.ERROR
                )
                Notifications.Bus.notify(errorNotification, project)
            }
        }
    }
    
    private fun getLuaPandaResourceContent(): ByteArray {
        try {

            // 使用插件管理器获取插件路径
            val pluginPath = try {
                val pluginDescriptor = PluginManagerCore.getPlugin(
                    PluginId.getId("com.fixcode.emmylua.enhanced")
                )
                
                if (pluginDescriptor != null) {
                    val pluginBasePath = pluginDescriptor.pluginPath
                    // 尝试多个可能的路径
                    val possiblePaths = listOf(
                        pluginBasePath?.resolve("LuaPanda.lua"),
                        pluginBasePath?.resolve("debugger")?.resolve("luapanda")?.resolve("LuaPanda.lua"),
                        pluginBasePath?.resolve("Debugger")?.resolve("luapanda")?.resolve("LuaPanda.lua")
                    )
                    
                    for (path in possiblePaths) {
                        if (path != null && path.toFile().exists()) {
                            return path.toFile().readBytes()
                        }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
            
            // 如果插件路径方式失败，尝试从开发环境获取
            try {
                val classLocation = File(javaClass.protectionDomain.codeSource.location.toURI())
                val projectRoot = if (classLocation.isDirectory) {
                    // 开发环境：从 build/classes 向上找到项目根目录
                    var current = classLocation
                    while (current.parent != null && !File(current, "LuaPanda.lua").exists()) {
                        current = current.parentFile
                    }
                    current
                } else {
                    // 打包环境：jar 文件所在目录
                    classLocation.parentFile
                }
                
                val luaPandaFile = File(projectRoot, "LuaPanda.lua")
                if (luaPandaFile.exists()) {
                    return luaPandaFile.readBytes()
                }
            } catch (e: Exception) {
                // 继续下一步
            }
            
            throw RuntimeException("无法找到 LuaPanda.lua 文件，请确保文件存在于资源目录或插件目录")
        } catch (e: Exception) {
            throw RuntimeException("读取 LuaPanda.lua 文件时发生错误: ${e.message}")
        }
    }
}
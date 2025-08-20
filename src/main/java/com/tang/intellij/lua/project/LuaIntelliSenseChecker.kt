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

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import java.io.File

/**
 * Lua IntelliSense checker that detects missing Lua code hint files
 * and prompts user to generate them when opening a project
 */
class LuaIntelliSenseChecker : ProjectManagerListener {
    
    @Deprecated("This method is deprecated in the parent class")
    override fun projectOpened(project: Project) {
        // 在后台线程执行文件系统操作，避免EDT违规
        ApplicationManager.getApplication().executeOnPooledThread {
            checkLuaIntelliSense(project)
        }
    }
    
    private fun checkLuaIntelliSense(project: Project) {
        val settings = LuaSettings.instance
        val projectPath = project.basePath ?: return
        val ideaFolder = File(projectPath, ".idea")
        
        if (!ideaFolder.exists()) return
        
        // Check project type and settings
        if (settings.projectType == LuaProjectType.UNREAL_ENGINE) {
            if (settings.enableUEIntelliSense) {
                checkUEIntelliSense(project, ideaFolder, settings)
            }
        } else {
            // For standard Lua projects, check if it looks like a UE project
            if (isUnrealEngineProject(projectPath)) {
                suggestUEProjectType(project)
            }
        }
    }
    
    private fun checkUEIntelliSense(project: Project, ideaFolder: File, settings: LuaSettings) {
        // Auto-detect UE project path if not set or invalid
        if (settings.ueProjectPath.isEmpty()) {
            val detectedPath = settings.autoDetectUProjectPath()
            if (detectedPath != null) {
                settings.ueProjectPath = detectedPath
                showAutoDetectedUEProjectNotification(project, detectedPath)
            }
        } else {
            // Validate existing UE project path
            val ueProjectFile = File(settings.ueProjectPath)
            if (!ueProjectFile.exists() || !ueProjectFile.name.endsWith(".uproject")) {
                // Try auto-detection if current path is invalid
                val detectedPath = settings.autoDetectUProjectPath()
                if (detectedPath != null) {
                    settings.ueProjectPath = detectedPath
                    showAutoDetectedUEProjectNotification(project, detectedPath)
                } else {
                    showInvalidUEProjectPathNotification(project)
                    return
                }
            }
        }
        
        // Check if UE.lua and UnLua.lua exist in .idea folder
        val ueLuaFile = File(ideaFolder, "UE.lua")
        val unLuaFile = File(ideaFolder, "UnLua.lua")
        
        if (!ueLuaFile.exists() || !unLuaFile.exists()) {
            showGenerateUEIntelliSenseNotification(project, ideaFolder)
        }
    }



    private fun isUnrealEngineProject(projectPath: String): Boolean {
        // Check for common UE project indicators
        val indicators = listOf(
            "Content/Script",
            "Plugins/UnLua",
            "Source"
        )
        
        val hasUProjectFile = File(projectPath).listFiles()?.any { 
            it.name.endsWith(".uproject") 
        } ?: false
        
        return hasUProjectFile || indicators.any { indicator ->
            File(projectPath, indicator).exists()
        }
    }
    
    private fun suggestUEProjectType(project: Project) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("EmmyLua.ProjectType")
        
        val notification = notificationGroup.createNotification(
            "Unreal Engine Project Detected",
            "This appears to be an Unreal Engine project. Would you like to set the project type to 'Unreal Engine' for better Lua support?",
            NotificationType.INFORMATION
        )
        
        notification.addAction(object : com.intellij.notification.NotificationAction("Set Project Type") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: com.intellij.notification.Notification) {
                val settings = LuaSettings.instance
                settings.projectType = LuaProjectType.UNREAL_ENGINE
                
                // Auto-detect UE project path when setting project type
                val detectedPath = settings.autoDetectUProjectPath()
                if (detectedPath != null) {
                    settings.ueProjectPath = detectedPath
                    showAutoDetectedUEProjectNotification(project, detectedPath)
                }
                
                notification.expire()
                
                // Re-check after setting project type
                checkLuaIntelliSense(project)
            }
        })
        
        notification.notify(project)
    }

    private fun showAutoDetectedUEProjectNotification(project: Project, detectedPath: String) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("EmmyLua.UEProject")
        
        val notification = notificationGroup.createNotification(
            "UE Project Auto-Detected",
            "Automatically detected UE project file: $detectedPath",
            NotificationType.INFORMATION
        )
        
        notification.notify(project)
    }

    private fun showInvalidUEProjectPathNotification(project: Project) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("EmmyLua.UEProject")
        
        val notification = notificationGroup.createNotification(
            "Invalid UE Project Path",
            "The specified UE project path is invalid. Please check your settings and ensure the .uproject file exists.",
            NotificationType.WARNING
        )
        
        notification.notify(project)
    }
    
    private fun showGenerateUEIntelliSenseNotification(project: Project, ideaFolder: File) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("EmmyLua.UEIntelliSense")
        
        val notification = notificationGroup.createNotification(
            "UE Lua IntelliSense",
            "Missing Unreal Engine Lua code hint files. Generate UE.lua and UnLua.lua for better code completion?",
            NotificationType.INFORMATION
        )
        
        notification.addAction(object : com.intellij.notification.NotificationAction("Generate") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: com.intellij.notification.Notification) {
                generateUEIntelliSenseFiles(project, ideaFolder)
                notification.expire()
            }
        })
        
        notification.addAction(object : com.intellij.notification.NotificationAction("Disable") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: com.intellij.notification.Notification) {
                val settings = LuaSettings.instance
                settings.enableUEIntelliSense = false
                notification.expire()
            }
        })
        
        notification.notify(project)
    }


    
    private fun generateUEIntelliSenseFiles(project: Project, ideaFolder: File) {
        try {
            val exporter = UEIntelliSenseExporter()
            val settings = LuaSettings.instance
            exporter.generateIntelliSenseFiles(project, ideaFolder, settings.ueProjectPath)
            
            showSuccessNotification(project, "Successfully generated UE.lua and UnLua.lua files!")
            
        } catch (e: Exception) {
            showErrorNotification(project, "Failed to generate UE IntelliSense files: ${e.message}")
        }
    }



    private fun showSuccessNotification(project: Project, message: String) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("EmmyLua.Success")
        
        val notification = notificationGroup.createNotification(
            "Lua IntelliSense",
            message,
            NotificationType.INFORMATION
        )
        
        notification.notify(project)
    }

    private fun showErrorNotification(project: Project, message: String) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("EmmyLua.Error")
        
        val notification = notificationGroup.createNotification(
            "Lua IntelliSense Error",
            message,
            NotificationType.ERROR
        )
        
        notification.notify(project)
    }


}
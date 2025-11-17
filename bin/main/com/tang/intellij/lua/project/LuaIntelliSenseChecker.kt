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
        
        // Check project type
        if (settings.projectType != LuaProjectType.UNREAL_ENGINE) {
            // For standard Lua projects, check if it looks like a UE project
            if (isUnrealEngineProject(projectPath)) {
                suggestUEProjectType(project)
            }
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
                
                notification.expire()
                
                // Re-check after setting project type
                checkLuaIntelliSense(project)
            }
        })
        
        notification.notify(project)
    }
}
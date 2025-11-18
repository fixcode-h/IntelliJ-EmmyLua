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

package com.tang.intellij.lua.stubs.index

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.util.indexing.FileBasedIndex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lua 索引错误通知管理器
 * 当检测到 Stub 索引损坏或不同步时，显示通知提示用户重建索引
 */
object LuaIndexNotification {
    private const val NOTIFICATION_GROUP_ID = "Lua Index Error"
    
    // 防止重复通知：每个项目只显示一次
    private val notifiedProjects = mutableSetOf<Project>()
    private val isNotifying = AtomicBoolean(false)
    
    /**
     * 显示索引错误通知
     * @param project 当前项目
     * @param fileName 出现错误的文件名（可选）
     * @param indexKey 出现错误的索引键（可选）
     */
    fun notifyIndexError(project: Project?, fileName: String? = null, indexKey: String? = null) {
        // 如果项目为空或已经通知过，则不再通知
        if (project == null || project in notifiedProjects) {
            return
        }
        
        // 使用原子操作确保只有一个线程可以发送通知
        if (!isNotifying.compareAndSet(false, true)) {
            return
        }
        
        try {
            notifiedProjects.add(project)
            
            // 构建通知消息
            val detailMessage = buildString {
                if (fileName != null) {
                    append("文件: $fileName")
                }
                if (indexKey != null) {
                    if (isNotEmpty()) append("<br/>")
                    append("索引: $indexKey")
                }
            }
            
            val message = if (detailMessage.isEmpty()) {
                "检测到 Lua 索引数据损坏，这可能影响代码补全和导航功能。<br/>建议重建索引以恢复完整功能。"
            } else {
                "检测到 Lua 索引数据损坏：<br/>$detailMessage<br/><br/>这可能影响代码补全和导航功能，建议重建索引以恢复完整功能。"
            }
            
            val notification = Notification(
                NOTIFICATION_GROUP_ID,
                "Lua 索引需要重建",
                message,
                NotificationType.WARNING
            )
            
            // 添加"重建索引"操作
            notification.addAction(object : NotificationAction("重建索引") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    rebuildIndex(project)
                    notification.expire()
                }
            })
            
            // 添加"稍后处理"操作
            notification.addAction(object : NotificationAction("稍后处理") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    notification.expire()
                }
            })
            
            // 发送通知
            Notifications.Bus.notify(notification, project)
            
        } finally {
            isNotifying.set(false)
        }
    }
    
    /**
     * 重建项目索引
     */
    private fun rebuildIndex(project: Project) {
        try {
            // 清除通知记录，允许下次重新通知
            notifiedProjects.remove(project)
            
            // 触发索引重建
            FileBasedIndex.getInstance().scheduleRebuild("用户请求重建 Lua 索引", object : Throwable() {
                override fun toString(): String {
                    return "Lua index corruption detected, user requested rebuild"
                }
            })
            
            // 显示成功通知
            val successNotification = Notification(
                NOTIFICATION_GROUP_ID,
                "索引重建已开始",
                "正在重建项目索引，这可能需要几分钟时间。索引重建完成后，代码补全功能将恢复正常。",
                NotificationType.INFORMATION
            )
            Notifications.Bus.notify(successNotification, project)
            
        } catch (e: Exception) {
            // 显示错误通知
            val errorNotification = Notification(
                NOTIFICATION_GROUP_ID,
                "索引重建失败",
                "无法自动重建索引。请尝试：File → Invalidate Caches → Invalidate and Restart",
                NotificationType.ERROR
            )
            Notifications.Bus.notify(errorNotification, project)
        }
    }
    
    /**
     * 清除项目的通知记录（用于项目关闭时）
     */
    fun clearNotificationRecord(project: Project) {
        notifiedProjects.remove(project)
    }
}


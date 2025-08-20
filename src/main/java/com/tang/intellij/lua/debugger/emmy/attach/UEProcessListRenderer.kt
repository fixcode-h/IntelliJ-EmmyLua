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

import com.tang.intellij.lua.debugger.emmy.attach.ProcessAttachmentManager
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/**
 * UE进程列表渲染器，支持分组显示和已附加状态标识
 */
class UEProcessListRenderer : DefaultListCellRenderer() {
    
    private val attachmentManager = ProcessAttachmentManager.getInstance()
    
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        
        if (value is ProcessInfo) {
            // 检查进程是否已被附加
            val isAttached = attachmentManager.isProcessAttached(value.pid)
            
            // 显示进程信息，如果已附加则添加标识
            text = if (isAttached) {
                "🔗 ${value.getDisplayText()} [已附加]"
            } else {
                value.getDisplayText()
            }
            
            // 设置工具提示
            toolTipText = buildTooltipText(value, isAttached)
            
            // 设置颜色和字体
            setProcessAppearance(value, isSelected, isAttached)
        }
        
        return this
    }
    
    /**
     * 设置进程外观（颜色和字体）
     */
    private fun setProcessAppearance(processInfo: ProcessInfo, isSelected: Boolean, isAttached: Boolean) {
        if (isSelected) return // 选中状态使用默认颜色
        
        // 已附加的进程使用特殊样式
        if (isAttached) {
            foreground = Color(0xF44336) // 红色表示已附加
            font = font.deriveFont(Font.BOLD or Font.ITALIC)
            return
        }
        
        when (processInfo.ueProcessType) {
            UEProcessType.EDITOR -> {
                foreground = Color(0x4CAF50)
                font = font.deriveFont(Font.BOLD)
            }
            UEProcessType.GAME -> {
                foreground = Color(0x2196F3)
                font = font.deriveFont(Font.BOLD)
            }
            UEProcessType.DSSERVER -> {
                foreground = Color(0xFF9800)
                font = font.deriveFont(Font.BOLD)
            }
            UEProcessType.BUILD_TOOL -> {
                foreground = Color(0x9E9E9E)
                font = font.deriveFont(Font.ITALIC)
            }
            UEProcessType.OTHER -> {
                foreground = Color(0x607D8B)
            }
            UEProcessType.NON_UE -> {
                // 使用默认颜色
            }
        }
    }
    
    /**
     * 构建工具提示文本
     */
    private fun buildTooltipText(processInfo: ProcessInfo, isAttached: Boolean): String {
        return buildString {
            if (isAttached) {
                val attachedInfo = attachmentManager.getAttachedProcessInfo(processInfo.pid)
                appendLine("⚠️ 此进程已被附加调试")
                if (attachedInfo != null) {
                    appendLine("附加时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(attachedInfo.attachTime))}")
                }
                appendLine("")
            }
            appendLine("进程类型: ${processInfo.ueProcessType.description}")
            appendLine("进程ID: ${processInfo.pid}")
            appendLine("进程名称: ${processInfo.name}")
            if (processInfo.title.isNotEmpty()) {
                appendLine("窗口标题: ${processInfo.title}")
            }
            appendLine("可执行文件路径: ${processInfo.path}")
            if (isAttached) {
                appendLine("")
                appendLine("提示: 请先断开现有调试会话再重新附加")
            }
        }.trim()
    }
}
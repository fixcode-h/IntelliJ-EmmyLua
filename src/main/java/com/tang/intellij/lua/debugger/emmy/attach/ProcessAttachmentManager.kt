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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程附加状态管理器
 * 用于跟踪已附加的进程，防止重复附加
 */
@Service
class ProcessAttachmentManager {
    
    companion object {
        fun getInstance(): ProcessAttachmentManager {
            return ApplicationManager.getApplication().getService(ProcessAttachmentManager::class.java)
        }
    }
    
    // 存储已附加的进程信息：PID -> AttachmentInfo
    private val attachedProcesses = ConcurrentHashMap<Int, AttachmentInfo>()
    
    /**
     * 附加信息
     */
    data class AttachmentInfo(
        val pid: Int,
        val processName: String,
        val session: XDebugSession,
        val attachTime: Long = System.currentTimeMillis()
    )
    
    /**
     * 检查进程是否已被附加
     */
    fun isProcessAttached(pid: Int): Boolean {
        return attachedProcesses.containsKey(pid)
    }
    
    /**
     * 获取已附加的进程信息
     */
    fun getAttachedProcessInfo(pid: Int): AttachmentInfo? {
        return attachedProcesses[pid]
    }
    
    /**
     * 获取所有已附加的进程PID列表
     */
    fun getAttachedProcessIds(): Set<Int> {
        return attachedProcesses.keys.toSet()
    }
    
    /**
     * 记录进程附加
     */
    fun recordProcessAttachment(pid: Int, processName: String, session: XDebugSession) {
        val attachmentInfo = AttachmentInfo(pid, processName, session)
        attachedProcesses[pid] = attachmentInfo
        
        // 监听调试会话结束事件，自动清理记录
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionStopped() {
                removeProcessAttachment(pid)
            }
            
            override fun sessionPaused() {
                // 会话暂停时不需要特殊处理
            }
            
            override fun sessionResumed() {
                // 会话恢复时不需要特殊处理
            }
            
            override fun stackFrameChanged() {
                // 栈帧变化时不需要特殊处理
            }
            
            override fun beforeSessionResume() {
                // 会话恢复前不需要特殊处理
            }
        })
    }
    
    /**
     * 移除进程附加记录
     */
    fun removeProcessAttachment(pid: Int) {
        attachedProcesses.remove(pid)
    }
    
    /**
     * 清理所有附加记录（通常在插件卸载时调用）
     */
    fun clearAllAttachments() {
        attachedProcesses.clear()
    }
    
    /**
     * 获取附加状态摘要信息
     */
    fun getAttachmentSummary(): String {
        val count = attachedProcesses.size
        if (count == 0) {
            return "当前没有已附加的进程"
        }
        
        val processInfo = attachedProcesses.values.joinToString(", ") { 
            "${it.processName}(PID:${it.pid})" 
        }
        return "已附加 $count 个进程: $processInfo"
    }
}
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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.tang.intellij.lua.debugger.emmy.attach.ProcessAttachmentManager
import com.tang.intellij.lua.psi.LuaFileUtil
import com.tang.intellij.lua.project.LuaProjectSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.charset.Charset
import javax.swing.*

/**
 * 进程选择器
 */
class ProcessSelector(private val project: Project) {

    /**
     * 获取系统进程列表
     * @param processName 进程名称过滤
     * @param autoAttachSingleProcess 是否自动附加单个进程
     * @param filterUEProcesses 是否只显示UE进程（true=只显示UE进程，false=显示所有进程）
     */
    fun getProcessList(
        processName: String = "",
        autoAttachSingleProcess: Boolean = true,
        filterUEProcesses: Boolean = true
    ): List<ProcessInfo> {

        return try {
             // 验证调试器工具
             val validationError = DebuggerPathUtils.validateDebuggerTools()
             if (validationError != null) {
                 throw Exception(validationError)
             }

             val toolPath = DebuggerPathUtils.getEmmyToolPath(WinArch.X86)
                 ?: throw Exception("找不到Emmy调试工具(x86版本)")

                         val processBuilder = ProcessBuilder(toolPath, "list_processes")
             processBuilder.directory(File(toolPath).parentFile)
            val process = processBuilder.start()
            
            val output = process.inputStream.readBytes()
            process.waitFor()
            
            // 使用CP936编码解析输出(中文系统)
            val outputStr = String(output, Charset.forName("CP936"))
            parseProcessList(outputStr, processName, emptyList(), filterUEProcesses)
        } catch (e: Exception) {
            throw Exception("获取进程列表失败: ${e.message}")
        }
    }

    /**
     * 解析进程列表输出
     * @param filterUEProcesses 是否只显示UE进程（true=只显示UE进程，false=显示所有进程）
     */
    private fun parseProcessList(
        output: String,
        processName: String,
        threadFilterBlacklist: List<String>,
        filterUEProcesses: Boolean = true
    ): List<ProcessInfo> {
        val lines = output.split("\r\n")
        val processes = mutableListOf<ProcessInfo>()
        val size = lines.size / 4

        for (i in 0 until size) {
            try {
                val pid = lines[i * 4].toIntOrNull() ?: continue
                val title = lines[i * 4 + 1]
                val path = lines[i * 4 + 2]
                val name = File(path).name

                val processInfo = ProcessInfo(pid, name, title, path)

                // 应用过滤条件
                val projectSettings = LuaProjectSettings.getInstance(project)
                val ueProcessNames = projectSettings.ueProcessNames.toList()
                val isUEProcess = processInfo.isUnrealEngineProcess(ueProcessNames)
                // 使用插件设置中的黑名单
                val debugProcessBlacklist = projectSettings.debugProcessBlacklist.toList()
                val isBlacklisted = processInfo.isInBlacklist(debugProcessBlacklist)
                // 过滤掉构建工具进程
                val isBuildTool = processInfo.ueProcessType == UEProcessType.BUILD_TOOL
                
                // 根据 filterUEProcesses 配置决定过滤逻辑
                val shouldInclude = if (filterUEProcesses) {
                    // 勾选：只显示UE进程，排除黑名单和构建工具
                    isUEProcess && !isBlacklisted && !isBuildTool
                } else {
                    // 不勾选：显示所有进程，但仍排除黑名单
                    !isBlacklisted
                }

                if (shouldInclude) {
                    processes.add(processInfo)
                }
            } catch (e: Exception) {
                // 忽略解析错误的行
                continue
            }
        }

        // 按进程类型排序
        return processes.sortedWith(compareBy<ProcessInfo> { 
            UEProcessClassifier.getProcessTypePriority(it.ueProcessType) 
        }.thenBy { it.name }.thenBy { it.pid })
    }

    /**
     * 显示进程选择对话框
     * @param filterUEProcesses 是否只显示UE进程（true=只显示UE进程，false=显示所有进程）
     */
    fun showProcessSelectionDialog(
        processName: String = "",
        autoAttachSingleProcess: Boolean = true,
        filterUEProcesses: Boolean = true
    ): ProcessInfo? {
        return ProgressManager.getInstance().run(object : Task.WithResult<ProcessInfo?, Exception>(
            project, "获取进程列表...", true
        ) {
            override fun compute(indicator: ProgressIndicator): ProcessInfo? {
                indicator.text = "正在获取系统进程列表..."
                
                val processes = try {
                    getProcessList(processName, autoAttachSingleProcess, filterUEProcesses)
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, e.message, "错误")
                    }
                    return null
                }

                if (indicator.isCanceled) return null

                var result: ProcessInfo? = null
                ApplicationManager.getApplication().invokeAndWait {
                    result = when {
                        processes.isEmpty() -> {
                            Messages.showWarningDialog(project, "没有找到匹配的进程", "提示")
                            null
                        }
                        processes.size == 1 && autoAttachSingleProcess -> {
                            processes[0]
                        }
                        else -> {
                            showProcessListDialog(processes)
                        }
                    }
                }
                return result
            }
        })
    }

    /**
     * 显示进程列表选择对话框
     */
    private fun showProcessListDialog(processes: List<ProcessInfo>): ProcessInfo? {
        val model = CollectionListModel(processes)
        val list = JBList(model)
        
        // 设置自定义渲染器，支持分组显示
        list.cellRenderer = UEProcessListRenderer()
        
        // 默认选中第一个进程
        if (processes.isNotEmpty()) {
            list.selectedIndex = 0
        }

        val scrollPane = JBScrollPane(list)
        scrollPane.preferredSize = Dimension(800, 400)

        val panel = JPanel(BorderLayout())
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // 创建说明标签
        val attachmentManager = ProcessAttachmentManager.getInstance()
        val attachedCount = attachmentManager.getAttachedProcessIds().size
        val attachedInfo = if (attachedCount > 0) {
            "<br><small style='color: #F44336;'>⚠️ 当前有 $attachedCount 个进程已附加，已附加进程显示为红色</small>"
        } else {
            ""
        }
        
        val label = JLabel("<html>选择要附加调试的进程:<br><small>UE进程已标记类型: 🎨编辑器 🎮游戏 🖥️服务器 🔧工具</small>$attachedInfo</html>")
        label.border = JBUI.Borders.emptyBottom(8)
        panel.add(label, BorderLayout.NORTH)

        // 添加双击事件
        var selectedProcess: ProcessInfo? = null
        var doubleClickSelected = false
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val index = list.locationToIndex(e.point)
                    if (index >= 0) {
                        val process = processes[index]
                        // 检查进程是否已被附加
                        if (attachmentManager.isProcessAttached(process.pid)) {
                            val attachedInfo = attachmentManager.getAttachedProcessInfo(process.pid)
                            Messages.showWarningDialog(
                                project,
                                "进程 ${process.name}(PID:${process.pid}) 已经被附加调试。\n" +
                                "附加时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(attachedInfo?.attachTime ?: 0))}\n" +
                                "请选择其他进程或先断开现有调试会话。",
                                "进程已附加"
                            )
                            return
                        }
                        selectedProcess = process
                        doubleClickSelected = true
                        SwingUtilities.getWindowAncestor(list).dispose()
                    }
                }
            }
        })

        val dialog = com.intellij.openapi.ui.DialogBuilder(project)
        dialog.setTitle("选择UE进程 - 类型标识")
        dialog.setCenterPanel(panel)
        dialog.addOkAction()
        dialog.addCancelAction()
        
        return if (dialog.showAndGet() || doubleClickSelected) {
            selectedProcess ?: list.selectedValue
        } else {
            null
        }
    }
}

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
import com.tang.intellij.lua.psi.LuaFileUtil
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

    private val ueProcessNames = listOf(
        "UnrealEngine", "UE4Editor", "UE5Editor", "UnrealEditor",
        "UnrealHeaderTool", "UnrealBuildTool", "UnrealLightmass"
    )

    /**
     * 获取系统进程列表
     */
         fun getProcessList(
         processName: String = "",
         filterUEProcesses: Boolean = false,
         autoAttachSingleProcess: Boolean = true,
         threadFilterBlacklist: List<String> = emptyList()
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
            parseProcessList(outputStr, processName, filterUEProcesses, threadFilterBlacklist)
        } catch (e: Exception) {
            throw Exception("获取进程列表失败: ${e.message}")
        }
    }

    /**
     * 解析进程列表输出
     */
    private fun parseProcessList(
        output: String,
        processName: String,
        filterUEProcesses: Boolean,
        threadFilterBlacklist: List<String>
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
                val matchesProcessName = processInfo.matchesProcessName(processName)
                val isUEProcess = processInfo.isUnrealEngineProcess(ueProcessNames)
                val isBlacklisted = processInfo.isInBlacklist(threadFilterBlacklist)
                val shouldInclude = matchesProcessName && 
                                 (!filterUEProcesses || isUEProcess) && 
                                 !isBlacklisted

                if (shouldInclude) {
                    processes.add(processInfo)
                }
            } catch (e: Exception) {
                // 忽略解析错误的行
                continue
            }
        }

        return processes
    }

    /**
     * 显示进程选择对话框
     */
    fun showProcessSelectionDialog(
        processName: String = "",
        filterUEProcesses: Boolean = false,
        autoAttachSingleProcess: Boolean = true,
        threadFilterBlacklist: List<String> = emptyList()
    ): ProcessInfo? {
        return ProgressManager.getInstance().run(object : Task.WithResult<ProcessInfo?, Exception>(
            project, "获取进程列表...", true
        ) {
            override fun compute(indicator: ProgressIndicator): ProcessInfo? {
                indicator.text = "正在获取系统进程列表..."
                
                val processes = try {
                    getProcessList(processName, filterUEProcesses, autoAttachSingleProcess, threadFilterBlacklist)
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
        
        // 设置渲染器
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is ProcessInfo) {
                    text = value.getDisplayText()
                    toolTipText = "${value.getDescriptionText()}\n${value.getDetailText()}"
                }
                return this
            }
        }

        val scrollPane = JBScrollPane(list)
        scrollPane.preferredSize = Dimension(600, 400)

        val panel = JPanel(BorderLayout())
        panel.add(scrollPane, BorderLayout.CENTER)
        
        val label = JLabel("选择要附加调试的进程:")
        label.border = JBUI.Borders.emptyBottom(8)
        panel.add(label, BorderLayout.NORTH)

        // 添加双击事件
        var selectedProcess: ProcessInfo? = null
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val index = list.locationToIndex(e.point)
                    if (index >= 0) {
                        selectedProcess = processes[index]
                        SwingUtilities.getWindowAncestor(list).dispose()
                    }
                }
            }
        })

        val dialog = com.intellij.openapi.ui.DialogBuilder(project)
        dialog.setTitle("选择进程")
        dialog.setCenterPanel(panel)
        dialog.addOkAction()
        dialog.addCancelAction()
        
        return if (dialog.showAndGet()) {
            selectedProcess ?: list.selectedValue
        } else {
            null
        }
    }
} 
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

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.tang.intellij.lua.debugger.emmy.EmmyWinArch
import org.jetbrains.annotations.NotNull
import java.awt.BorderLayout
import javax.swing.*

/**
 * Emmy附加调试设置面板
 */
class EmmyAttachDebugSettingsPanel(private val project: Project) : SettingsEditor<EmmyAttachDebugConfiguration>() {

    // UI组件
    private val processNameField = JBTextField()
    private val pidField = JBTextField()
    private val selectProcessButton = JButton("选择进程")
    private val x64RadioButton = JRadioButton("x64")
    private val x86RadioButton = JRadioButton("x86") 
    private val captureLogCheckBox = JCheckBox("捕获日志")
    private val autoAttachSingleCheckBox = JCheckBox("自动附加单个进程")
    private val filterUEProcessesCheckBox = JCheckBox("过滤虚幻引擎进程")
    private val blacklistField = JBTextField()

    private val panel: JPanel
    private val processSelector = ProcessSelector(project)

    init {
        // 创建架构选择按钮组
        val archGroup = ButtonGroup()
        archGroup.add(x64RadioButton)
        archGroup.add(x86RadioButton)
        x64RadioButton.isSelected = true

        // 设置仅在Windows系统显示架构选择
        x64RadioButton.isVisible = SystemInfoRt.isWindows
        x86RadioButton.isVisible = SystemInfoRt.isWindows

        // 设置字段属性 - PID字段可编辑，但建议通过按钮选择
        pidField.isEditable = true
        pidField.toolTipText = "进程ID，建议通过「选择进程」按钮选择，也可手动输入。每次启动调试都会重置为0"
        processNameField.toolTipText = "用于过滤进程列表的进程名称，支持部分匹配"
        blacklistField.toolTipText = "用逗号分隔的进程过滤黑名单"
        blacklistField.text = "winlogon,csrss,wininit,services"  // 设置默认黑名单

        // 选择进程按钮事件
        selectProcessButton.addActionListener { selectProcess() }

        // 架构选择事件
        x64RadioButton.addActionListener { fireEditorStateChanged() }
        x86RadioButton.addActionListener { fireEditorStateChanged() }

        // 其他组件事件
        captureLogCheckBox.addActionListener { fireEditorStateChanged() }
        autoAttachSingleCheckBox.addActionListener { fireEditorStateChanged() }
        filterUEProcessesCheckBox.addActionListener { fireEditorStateChanged() }

        // 创建面板布局
        panel = createPanel()
    }

    private fun createPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        
        // 进程选择部分
        val processNamePanel = JPanel(BorderLayout())
        processNamePanel.add(JLabel("进程名称过滤:"), BorderLayout.WEST)
        processNamePanel.add(processNameField, BorderLayout.CENTER)
        panel.add(processNamePanel)
        
        val processPanel = JPanel(BorderLayout())
        processPanel.add(JLabel("目标进程PID:"), BorderLayout.WEST)
        val pidPanel = JPanel(BorderLayout())
        pidPanel.add(pidField, BorderLayout.CENTER)
        pidPanel.add(selectProcessButton, BorderLayout.EAST)
        processPanel.add(pidPanel, BorderLayout.CENTER)
        panel.add(processPanel)

        // 架构选择部分（仅Windows）
        if (SystemInfoRt.isWindows) {
            val archPanel = JPanel(BorderLayout())
            archPanel.add(JLabel("目标架构:"), BorderLayout.WEST)
            val radioPanel = JPanel()
            radioPanel.add(x64RadioButton)
            radioPanel.add(x86RadioButton)
            archPanel.add(radioPanel, BorderLayout.CENTER)
            panel.add(archPanel)
        }

        // 调试选项
        panel.add(captureLogCheckBox)
        panel.add(autoAttachSingleCheckBox)
        panel.add(filterUEProcessesCheckBox)
        
        val blacklistPanel = JPanel(BorderLayout())
        blacklistPanel.add(JLabel("进程黑名单:"), BorderLayout.WEST)
        blacklistPanel.add(blacklistField, BorderLayout.CENTER)
        panel.add(blacklistPanel)

        // 添加使用说明
        val helpText = JTextArea()
        helpText.text = "使用说明:\n" +
            "1. 进程名称过滤: 输入关键词过滤进程列表，支持部分匹配\n" +
            "2. 目标进程选择: 默认PID为0，必须通过\"选择进程\"按钮选择目标进程\n" +
            "3. 架构选择: Windows系统会自动检测进程架构，也可手动选择\n" +
            "4. 调试选项: 可启用日志捕获、自动附加等功能\n" +
            "5. 进程过滤: 支持虚幻引擎进程过滤和黑名单过滤\n" +
            "6. 使用前请确保目标程序已集成emmylua调试库"
        helpText.isEditable = false
        helpText.background = panel.background
        helpText.border = JBUI.Borders.emptyTop(10)
        helpText.font = helpText.font.deriveFont(helpText.font.size - 1.0f)
        helpText.rows = 8
        
        val scrollPane = JScrollPane(helpText)
        scrollPane.border = JBUI.Borders.emptyTop(10)
        panel.add(scrollPane)

        return panel
    }

    /**
     * 选择进程
     */
    private fun selectProcess() {
        val processName = processNameField.text.trim()
        val filterUE = filterUEProcessesCheckBox.isSelected
        val autoAttachSingle = autoAttachSingleCheckBox.isSelected
        val blacklist = blacklistField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val selectedProcess = processSelector.showProcessSelectionDialog(
            processName, filterUE, autoAttachSingle, blacklist
        )

        selectedProcess?.let { process ->
            pidField.text = process.pid.toString()
            // 如果进程名称字段为空，自动填充
            if (processNameField.text.trim().isEmpty()) {
                processNameField.text = process.name
            }
            fireEditorStateChanged()
        }
    }

    override fun resetEditorFrom(@NotNull configuration: EmmyAttachDebugConfiguration) {
        // 总是显示空的PID，确保用户每次都需要选择进程
        pidField.text = ""
        processNameField.text = configuration.processName
        
        if (SystemInfoRt.isWindows) {
            when (configuration.winArch) {
                EmmyWinArch.X64 -> x64RadioButton.isSelected = true
                EmmyWinArch.X86 -> x86RadioButton.isSelected = true
            }
        }

        captureLogCheckBox.isSelected = configuration.captureLog
        autoAttachSingleCheckBox.isSelected = configuration.autoAttachSingleProcess
        filterUEProcessesCheckBox.isSelected = configuration.filterUEProcesses
        blacklistField.text = configuration.threadFilterBlacklist.joinToString(",")
    }

    override fun applyEditorTo(@NotNull configuration: EmmyAttachDebugConfiguration) {
        // 如果用户没有选择进程（PID为空或0），则保持为0
        val inputPid = pidField.text.trim()
        configuration.pid = if (inputPid.isEmpty()) 0 else inputPid.toIntOrNull() ?: 0
        configuration.processName = processNameField.text.trim()
        
        if (SystemInfoRt.isWindows) {
            configuration.winArch = if (x64RadioButton.isSelected) EmmyWinArch.X64 else EmmyWinArch.X86
        }

        configuration.captureLog = captureLogCheckBox.isSelected
        configuration.autoAttachSingleProcess = autoAttachSingleCheckBox.isSelected
        configuration.filterUEProcesses = filterUEProcessesCheckBox.isSelected
        configuration.threadFilterBlacklist = blacklistField.text.split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
    }

    @NotNull
    override fun createEditor(): JComponent {
        return panel
    }
} 
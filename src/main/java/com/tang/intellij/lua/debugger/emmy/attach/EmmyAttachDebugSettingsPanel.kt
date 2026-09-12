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
import com.intellij.util.ui.JBUI
import com.tang.intellij.lua.debugger.emmy.EmmyWinArch
import com.tang.intellij.lua.debugger.DebugLogLevel
import org.jetbrains.annotations.NotNull
import java.awt.BorderLayout
import javax.swing.*

/**
 * Emmy附加调试设置面板
 */
class EmmyAttachDebugSettingsPanel(private val project: Project) : SettingsEditor<EmmyAttachDebugConfiguration>() {

    // UI组件
    private val x64RadioButton = JRadioButton("x64")
    private val x86RadioButton = JRadioButton("x86") 
    private val captureLogCheckBox = JCheckBox("捕获日志")
    private val autoAttachSingleCheckBox = JCheckBox("自动附加单个进程")
    private val filterUEProcessesCheckBox = JCheckBox("过滤虚幻引擎进程")
    private val autoAuthorizeCliCheckBox = JCheckBox("自动授权本机 CLI/AI 客户端访问本次会话")

    private val logLevelComboBox = JComboBox(DebugLogLevel.entries.toTypedArray())

    private val panel: JPanel

    init {
        // 创建架构选择按钮组
        val archGroup = ButtonGroup()
        archGroup.add(x64RadioButton)
        archGroup.add(x86RadioButton)
        x64RadioButton.isSelected = true

        // 设置仅在Windows系统显示架构选择
        x64RadioButton.isVisible = SystemInfoRt.isWindows
        x86RadioButton.isVisible = SystemInfoRt.isWindows

        // 架构选择事件
        x64RadioButton.addActionListener { fireEditorStateChanged() }
        x86RadioButton.addActionListener { fireEditorStateChanged() }

        // 其他组件事件
        captureLogCheckBox.addActionListener { fireEditorStateChanged() }
        autoAttachSingleCheckBox.addActionListener { fireEditorStateChanged() }
        filterUEProcessesCheckBox.addActionListener { fireEditorStateChanged() }
        autoAuthorizeCliCheckBox.addActionListener { fireEditorStateChanged() }
        autoAuthorizeCliCheckBox.toolTipText =
            "勾选后，attach 会话建立时会自动把本机 CLI/AI 客户端（默认 emmy-debug）加入授权名单；\n" +
                "仅对受信任的项目生效，客户端名单可在 Settings → Languages & Frameworks → EmmyLua → Debugger 中配置。\n" +
                "该能力可读取被调试进程的值并控制执行，请按需开启。"
        logLevelComboBox.addActionListener { fireEditorStateChanged() }
        
        // 设置日志等级默认值和提示
        logLevelComboBox.selectedItem = DebugLogLevel.RUNTIME
        logLevelComboBox.toolTipText = "设置日志输出等级：Log0=调试，Log1=运行，Log2=警告，Log3=错误"

        // 创建面板布局
        panel = createPanel()
    }

    private fun createPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

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
        panel.add(autoAuthorizeCliCheckBox)
        
        // 日志等级选择
        val logLevelPanel = JPanel(BorderLayout())
        logLevelPanel.add(JLabel("日志输出等级:"), BorderLayout.WEST)
        logLevelPanel.add(logLevelComboBox, BorderLayout.CENTER)
        panel.add(logLevelPanel)

        // 添加使用说明
        val helpText = JTextArea()
        helpText.text = "使用说明:\n" +
            "1. 目标进程选择: 启动调试时选择目标进程\n" +
            "2. 架构选择: Windows系统会自动检测进程架构，也可手动选择\n" +
            "3. 调试选项: 可启用日志捕获、自动附加等功能\n" +
            "4. 进程过滤: 支持虚幻引擎进程过滤，黑名单过滤可在插件设置中配置\n" +
            "5. 使用前请确保目标程序已集成emmylua调试库"
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

    override fun resetEditorFrom(@NotNull configuration: EmmyAttachDebugConfiguration) {
        if (SystemInfoRt.isWindows) {
            when (configuration.winArch) {
                EmmyWinArch.X64 -> x64RadioButton.isSelected = true
                EmmyWinArch.X86 -> x86RadioButton.isSelected = true
            }
        }

        captureLogCheckBox.isSelected = configuration.captureLog
        autoAttachSingleCheckBox.isSelected = configuration.autoAttachSingleProcess
        filterUEProcessesCheckBox.isSelected = configuration.filterUEProcesses
        autoAuthorizeCliCheckBox.isSelected = configuration.autoAuthorizeCliClients
        logLevelComboBox.selectedItem = configuration.logLevel
    }

    override fun applyEditorTo(@NotNull configuration: EmmyAttachDebugConfiguration) {
        configuration.pid = 0
        configuration.processName = ""
        
        if (SystemInfoRt.isWindows) {
            configuration.winArch = if (x64RadioButton.isSelected) EmmyWinArch.X64 else EmmyWinArch.X86
        }

        configuration.captureLog = captureLogCheckBox.isSelected
        configuration.autoAttachSingleProcess = autoAttachSingleCheckBox.isSelected
        configuration.filterUEProcesses = filterUEProcessesCheckBox.isSelected
        configuration.autoAuthorizeCliClients = autoAuthorizeCliCheckBox.isSelected
        configuration.logLevel = logLevelComboBox.selectedItem as DebugLogLevel
        // 使用插件设置中的黑名单
        configuration.threadFilterBlacklist = emptyList()
    }

    @NotNull
    override fun createEditor(): JComponent {
        return panel
    }
}

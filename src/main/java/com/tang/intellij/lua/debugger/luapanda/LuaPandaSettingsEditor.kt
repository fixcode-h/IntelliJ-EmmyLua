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

package com.tang.intellij.lua.debugger.luapanda

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class LuaPandaSettingsEditor : SettingsEditor<LuaPandaDebugConfiguration>() {
    
    private val transportTypeCombo = ComboBox(LuaPandaTransportType.values())
    private val hostField = JBTextField()
    private val portSpinner = JSpinner(SpinnerNumberModel(8818, 1, 65535, 1))
    private val stopOnEntryCheckBox = JBCheckBox("Stop on entry")
    private val useCHookCheckBox = JBCheckBox("Use C Hook")
    private val logLevelSpinner = JSpinner(SpinnerNumberModel(1, 0, 3, 1))

    override fun createEditor(): JComponent {
        val panel = JPanel(BorderLayout())
        
        val formBuilder = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Transport Type:"), transportTypeCombo)
            .addLabeledComponent(JBLabel("Host:"), hostField)
            .addLabeledComponent(JBLabel("Port:"), portSpinner)
            .addComponent(stopOnEntryCheckBox)
            .addComponent(useCHookCheckBox)
            .addLabeledComponent(JBLabel("Log Level:"), logLevelSpinner)
        
        panel.add(formBuilder.panel, BorderLayout.NORTH)
        
        // 添加传输类型变化监听器
        transportTypeCombo.addActionListener {
            val isClient = transportTypeCombo.selectedItem == LuaPandaTransportType.TCP_CLIENT
            hostField.isEnabled = isClient
        }
        
        return panel
    }

    override fun resetEditorFrom(configuration: LuaPandaDebugConfiguration) {
        transportTypeCombo.selectedItem = configuration.transportType
        hostField.text = configuration.host
        portSpinner.value = configuration.port
        stopOnEntryCheckBox.isSelected = configuration.stopOnEntry
        useCHookCheckBox.isSelected = configuration.useCHook
        logLevelSpinner.value = configuration.logLevel
        
        // 更新主机字段状态
        val isClient = configuration.transportType == LuaPandaTransportType.TCP_CLIENT
        hostField.isEnabled = isClient
    }

    override fun applyEditorTo(configuration: LuaPandaDebugConfiguration) {
        configuration.transportType = transportTypeCombo.selectedItem as LuaPandaTransportType
        configuration.host = hostField.text
        configuration.port = portSpinner.value as Int
        configuration.stopOnEntry = stopOnEntryCheckBox.isSelected
        configuration.useCHook = useCHookCheckBox.isSelected
        configuration.logLevel = logLevelSpinner.value as Int
    }
}
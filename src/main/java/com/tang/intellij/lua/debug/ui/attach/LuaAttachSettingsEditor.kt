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

package com.tang.intellij.lua.debug.ui.attach

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import com.tang.intellij.lua.debug.api.LuaLocalProcess
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import com.tang.intellij.lua.debug.ui.process.LuaLocalProcessScanner
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class LuaAttachSettingsEditor(private val project: Project) : SettingsEditor<LuaAttachConfiguration>() {
    private val processName = JBTextField()
    private val pickButton = JButton(LuaDebugBundle.message("attach.config.pick"))
    private val waitForProcess = JBCheckBox(LuaDebugBundle.message("attach.config.wait"))
    private val waitSeconds = JSpinner(SpinnerNumberModel(60, 1, 3600, 5))
    private val captureOutput = JBCheckBox(LuaDebugBundle.message("attach.config.captureOutput"))

    private val root: JComponent = panel {
        row(LuaDebugBundle.message("attach.config.process")) {
            cell(processName).align(AlignX.FILL).resizableColumn()
                .comment(LuaDebugBundle.message("attach.config.process.comment"))
            cell(pickButton)
        }
        row {
            cell(waitForProcess)
            cell(waitSeconds).enabledIf(waitForProcess.selected)
            label(LuaDebugBundle.message("attach.config.wait.seconds"))
        }
        row { cell(captureOutput) }
        row {
            comment(LuaDebugBundle.message("attach.config.hint"))
        }
    }

    init {
        pickButton.addActionListener { pickProcess() }
    }

    override fun createEditor(): JComponent = root

    override fun resetEditorFrom(configuration: LuaAttachConfiguration) {
        val o = configuration.options
        processName.text = o.processName.orEmpty()
        waitForProcess.isSelected = o.waitForProcess
        waitSeconds.value = o.waitSeconds
        captureOutput.isSelected = o.captureOutput
    }

    override fun applyEditorTo(configuration: LuaAttachConfiguration) {
        val o = configuration.options
        o.processName = processName.text.trim()
        o.waitForProcess = waitForProcess.isSelected
        o.waitSeconds = waitSeconds.value as Int
        o.captureOutput = captureOutput.isSelected
    }

    /** 弹出当前检测到 Lua 的进程，选中后填进程名。扫描在后台做。 */
    private fun pickProcess() {
        pickButton.isEnabled = false
        val modality = ModalityState.stateForComponent(root)
        ApplicationManager.getApplication().executeOnPooledThread {
            val processes = LuaLocalProcessScanner.scan(LuaLocalProcessScanner.listProcesses()).values
                .filter { LuaLocalProcessScanner.isLuaCandidate(it) }
                .sortedBy { it.name.lowercase() }
            ApplicationManager.getApplication().invokeLater({
                pickButton.isEnabled = true
                if (processes.isEmpty()) {
                    JBPopupFactory.getInstance()
                        .createMessage(LuaDebugBundle.message("attach.config.pick.empty"))
                        .showUnderneathOf(pickButton)
                    return@invokeLater
                }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(processes.toList())
                    .setTitle(LuaDebugBundle.message("attach.config.pick.title"))
                    .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { describe(it) })
                    .setItemChosenCallback { processName.text = it.name }
                    .setNamerForFiltering { it.name }
                    .createPopup()
                    .showUnderneathOf(pickButton)
            }, modality)
        }
    }

    private fun describe(p: LuaLocalProcess): String {
        val parts = mutableListOf(p.name, p.pid.toString())
        p.arch?.let { parts += it }
        p.luaVersion?.let { parts += "Lua $it" }
        parts += LuaLocalProcessScanner.tags(p)
        return parts.joinToString("  ")
    }
}

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

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** 「Lua 附加」配置页：不填进程，只有开关（参照旧版 Emmy Attach）。 */
class LuaAttachSettingsEditor : SettingsEditor<LuaAttachConfiguration>() {
    private val autoAttachSingle = JBCheckBox(LuaDebugBundle.message("attach.config.autoSingle"))
    private val onlyLua = JBCheckBox(LuaDebugBundle.message("attach.config.onlyLua"))
    private val skipAttached = JBCheckBox(LuaDebugBundle.message("attach.config.skipAttached"))
    private val waitForProcess = JBCheckBox(LuaDebugBundle.message("attach.config.wait"))
    private val waitSeconds = JSpinner(SpinnerNumberModel(60, 1, 3600, 5))
    private val captureOutput = JBCheckBox(LuaDebugBundle.message("attach.config.captureOutput"))

    private val root: JComponent = panel {
        row { cell(autoAttachSingle).comment(LuaDebugBundle.message("attach.config.autoSingle.comment")) }
        row { cell(onlyLua).comment(LuaDebugBundle.message("attach.config.onlyLua.comment")) }
        row { cell(skipAttached) }
        row {
            cell(waitForProcess)
            cell(waitSeconds).enabledIf(waitForProcess.selected)
            label(LuaDebugBundle.message("attach.config.wait.seconds"))
        }
        row { cell(captureOutput) }
        row { comment(LuaDebugBundle.message("attach.config.hint")) }
    }

    override fun createEditor(): JComponent = root

    override fun resetEditorFrom(configuration: LuaAttachConfiguration) {
        val o = configuration.options
        autoAttachSingle.isSelected = o.autoAttachSingleProcess
        onlyLua.isSelected = o.onlyLuaProcesses
        skipAttached.isSelected = o.skipAttachedProcesses
        waitForProcess.isSelected = o.waitForProcess
        waitSeconds.value = o.waitSeconds
        captureOutput.isSelected = o.captureOutput
    }

    override fun applyEditorTo(configuration: LuaAttachConfiguration) {
        val o = configuration.options
        o.autoAttachSingleProcess = autoAttachSingle.isSelected
        o.onlyLuaProcesses = onlyLua.isSelected
        o.skipAttachedProcesses = skipAttached.isSelected
        o.waitForProcess = waitForProcess.isSelected
        o.waitSeconds = waitSeconds.value as Int
        o.captureOutput = captureOutput.isSelected
    }
}

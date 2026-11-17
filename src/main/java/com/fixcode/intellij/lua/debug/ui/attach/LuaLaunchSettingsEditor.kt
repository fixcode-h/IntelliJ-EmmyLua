package com.fixcode.intellij.lua.debug.ui.attach

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import javax.swing.JComponent

/** 「启动并附加」配置页：程序、参数、工作目录，外加两个开关。 */
class LuaLaunchSettingsEditor(project: Project) : SettingsEditor<LuaLaunchConfiguration>() {
    private val program = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("exe").withTitle(LuaDebugBundle.message("launch.program.choose")),
        )
        (textField as? JBTextField)?.emptyText?.text = LuaDebugBundle.message("launch.program.empty")
    }
    private val arguments = RawCommandLineEditor()
    private val workingDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
        (textField as? JBTextField)?.emptyText?.text = LuaDebugBundle.message("launch.workDir.empty")
    }
    private val terminateOnStop = JBCheckBox(LuaDebugBundle.message("launch.terminateOnStop"))
    private val captureOutput = JBCheckBox(LuaDebugBundle.message("attach.config.captureOutput"))

    private val root: JComponent = panel {
        row(LuaDebugBundle.message("launch.program")) { cell(program).align(AlignX.FILL) }
        row(LuaDebugBundle.message("launch.arguments")) {
            cell(arguments).align(AlignX.FILL).comment(LuaDebugBundle.message("launch.arguments.comment"))
        }
        row(LuaDebugBundle.message("launch.workDir")) { cell(workingDirectory).align(AlignX.FILL) }
        row { cell(terminateOnStop).comment(LuaDebugBundle.message("launch.terminateOnStop.comment")) }
        row { cell(captureOutput) }
        row { comment(LuaDebugBundle.message("launch.hint")) }
    }

    override fun createEditor(): JComponent = root

    override fun resetEditorFrom(configuration: LuaLaunchConfiguration) {
        val o = configuration.options
        program.text = o.program.orEmpty()
        arguments.text = o.programArguments.orEmpty()
        workingDirectory.text = o.workingDirectory.orEmpty()
        terminateOnStop.isSelected = o.terminateOnStop
        captureOutput.isSelected = o.captureOutput
    }

    override fun applyEditorTo(configuration: LuaLaunchConfiguration) {
        val o = configuration.options
        o.program = program.text.trim()
        o.programArguments = arguments.text
        o.workingDirectory = workingDirectory.text.trim()
        o.terminateOnStop = terminateOnStop.isSelected
        o.captureOutput = captureOutput.isSelected
    }
}

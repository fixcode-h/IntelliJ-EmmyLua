package com.tang.intellij.lua.debugger.luapanda

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * LuaPanda设置编辑器
 */
class LuaPandaSettingsEditor(private val project: Project) : SettingsEditor<LuaPandaRunConfiguration>() {
    
    private val debugPortField = JBTextField()
    private val workingDirField = TextFieldWithBrowseButton()
    private val luaExeField = TextFieldWithBrowseButton()
    private val scriptPathField = TextFieldWithBrowseButton()
    private val programArgsField = JBTextField()
    
    init {
        debugPortField.text = "8818"
        
        workingDirField.addBrowseFolderListener(
            "Select Working Directory",
            null,
            project,
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
        
        luaExeField.addBrowseFolderListener(
            "Select Lua Executable",
            null,
            project,
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor()
        )
        
        scriptPathField.addBrowseFolderListener(
            "Select Lua Script",
            null,
            project,
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor("lua")
        )
    }
    
    override fun createEditor(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Debug Port:", debugPortField)
            .addLabeledComponent("Working Directory:", workingDirField)
            .addLabeledComponent("Lua Executable:", luaExeField)
            .addLabeledComponent("Script Path:", scriptPathField)
            .addLabeledComponent("Program Arguments:", programArgsField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }
    
    override fun resetEditorFrom(configuration: LuaPandaRunConfiguration) {
        debugPortField.text = configuration.debugPort.toString()
        workingDirField.text = configuration.workingDir
        luaExeField.text = configuration.luaExe
        scriptPathField.text = configuration.scriptPath
        programArgsField.text = configuration.programArgs
    }
    
    override fun applyEditorTo(configuration: LuaPandaRunConfiguration) {
        configuration.debugPort = debugPortField.text.toIntOrNull() ?: 8818
        configuration.workingDir = workingDirField.text
        configuration.luaExe = luaExeField.text
        configuration.scriptPath = scriptPathField.text
        configuration.programArgs = programArgsField.text
    }
}
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

package com.tang.intellij.lua.refactoring.extract

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.tang.intellij.lua.LuaBundle
import com.tang.intellij.lua.refactoring.LuaRefactoringUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * 函数提取对话框
 * 允许用户配置提取函数的参数
 */
class LuaExtractMethodDialog(
    private val project: Project,
    private val operation: LuaExtractMethodOperation
) : DialogWrapper(project) {

    private lateinit var functionNameField: JBTextField
    private lateinit var parametersArea: JTextArea
    private lateinit var returnValuesArea: JTextArea

    init {
        title = LuaBundle.message("refactor.extract_method.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // 函数名输入框
        functionNameField = JBTextField(operation.getSuggestedFunctionName())
        
        // 参数列表显示
        parametersArea = JTextArea(3, 30).apply {
            text = operation.getInputVariables().joinToString("\n") { 
                LuaBundle.message("refactor.extract_method.parameter_prefix", it)
            }
            isEditable = false
            background = panel.background
        }
        
        // 返回值列表显示
        returnValuesArea = JTextArea(3, 30).apply {
            text = operation.getOutputVariables().joinToString("\n") { 
                LuaBundle.message("refactor.extract_method.return_value_prefix", it)
            }
            isEditable = false
            background = panel.background
        }

        val formPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(LuaBundle.message("refactor.extract_method.dialog.function_name"), functionNameField)
            .addLabeledComponentFillVertically(LuaBundle.message("refactor.extract_method.dialog.input_parameters"), parametersArea)
            .addLabeledComponentFillVertically(LuaBundle.message("refactor.extract_method.dialog.return_values"), returnValuesArea)
            .panel

        panel.add(formPanel, BorderLayout.CENTER)
        
        // 添加说明文本
        val infoLabel = JBLabel("<html><body>" +
            LuaBundle.message("refactor.extract_method.dialog.description") +
            "</body></html>")
        panel.add(infoLabel, BorderLayout.SOUTH)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val functionName = functionNameField.text.trim()
        
        if (functionName.isEmpty()) {
            return ValidationInfo(LuaBundle.message("refactor.extract_method.validation.empty_name"), functionNameField)
        }
        
        if (!LuaRefactoringUtil.isLuaIdentifier(functionName)) {
            return ValidationInfo(LuaBundle.message("refactor.extract_method.validation.invalid_identifier"), functionNameField)
        }
        
        return null
    }

    /**
     * 获取用户输入的函数名
     */
    fun getFunctionName(): String {
        return functionNameField.text.trim()
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return functionNameField
    }
}
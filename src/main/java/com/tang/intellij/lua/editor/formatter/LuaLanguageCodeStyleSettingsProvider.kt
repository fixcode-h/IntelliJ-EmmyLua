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

package com.tang.intellij.lua.editor.formatter

import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizableOptions
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.tang.intellij.lua.LuaBundle
import com.tang.intellij.lua.lang.LuaLanguage

/**

 * Created by tangzx on 2017/2/22.
 */
class LuaLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): LuaLanguage = LuaLanguage.INSTANCE

    override fun getCodeSample(settingsType: SettingsType): String {
        return CodeStyleAbstractPanel.readFromFile(this.javaClass, "preview.lua.template")
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor {
        return SmartIndentOptionsEditor()
    }

    /*override fun getDefaultCommonSettings(): CommonCodeStyleSettings {
        val commonSettings = CommonCodeStyleSettings(language)
        commonSettings.initIndentOptions()
        return commonSettings
    }*/

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        when (settingsType) {
            SettingsType.SPACING_SETTINGS -> {
                // Table spacing
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "SPACE_AFTER_TABLE_FIELD_SEP", LuaBundle.message("codestyle.spacing.table.around_assign"), LuaBundle.message("codestyle.spacing.table"))
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "SPACE_AROUND_BINARY_OPERATOR", "Around binary operator", CodeStyleSettingsCustomizableOptions.getInstance().SPACES_OTHER)
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "SPACE_INSIDE_INLINE_TABLE", "Inside inline table", LuaBundle.message("codestyle.spacing.table"))
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "SPACE_BETWEEN_TABLE_FIELDS", "Between table fields", LuaBundle.message("codestyle.spacing.table"))
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "SPACE_AFTER_COMMA_IN_TABLE", LuaBundle.message("codestyle.spacing.table.around_comma"), LuaBundle.message("codestyle.spacing.table"))
                
                // Function call spacing
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "SPACE_BEFORE_FUNCTION_CALL_PARENTHESES", LuaBundle.message("codestyle.spacing.function_call.around_parentheses"), LuaBundle.message("codestyle.spacing.function_call"))
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "SPACE_WITHIN_FUNCTION_CALL_PARENTHESES", "Within function call parentheses", LuaBundle.message("codestyle.spacing.function_call"))
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "SPACE_AFTER_COMMA_IN_FUNCTION_CALLS", LuaBundle.message("codestyle.spacing.function_call.around_comma"), LuaBundle.message("codestyle.spacing.function_call"))
                
                // Comment spacing
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "SPACE_BEFORE_LINE_COMMENT", LuaBundle.message("codestyle.spacing.comment.before_line"), LuaBundle.message("codestyle.spacing.comment"))
                
                consumer.showStandardOptions("SPACE_AROUND_ASSIGNMENT_OPERATORS",
                        "SPACE_BEFORE_COMMA",
                        "SPACE_AFTER_COMMA")
            }
            SettingsType.BLANK_LINES_SETTINGS -> {
                // Require statements
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "BLANK_LINES_AFTER_REQUIRE_BLOCK", LuaBundle.message("codestyle.blank_lines.require.after"), LuaBundle.message("codestyle.blank_lines.require"))
                
                // Functions
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "BLANK_LINES_BEFORE_FUNCTION", LuaBundle.message("codestyle.blank_lines.function.before"), LuaBundle.message("codestyle.blank_lines.function"))
                consumer.showCustomOption(LuaCodeStyleSettings::class.java, "BLANK_LINES_AFTER_FUNCTION", LuaBundle.message("codestyle.blank_lines.function.after"), LuaBundle.message("codestyle.blank_lines.function"))
            }
            SettingsType.WRAPPING_AND_BRACES_SETTINGS -> {
                consumer.showStandardOptions(
                        "METHOD_PARAMETERS_WRAP",
                        "ALIGN_MULTILINE_PARAMETERS",

                        "CALL_PARAMETERS_WRAP",
                        "ALIGN_MULTILINE_PARAMETERS_IN_CALLS",

                        // keep when reformatting
                        "KEEP_SIMPLE_BLOCKS_IN_ONE_LINE"
                )

                // Table alignment and wrapping
                consumer.showCustomOption(LuaCodeStyleSettings::class.java,
                        "ALIGN_TABLE_FIELD_ASSIGN",
                        LuaBundle.message("codestyle.wrapping.table.align_fields"),
                        LuaBundle.message("codestyle.wrapping.table"))
                consumer.showCustomOption(LuaCodeStyleSettings::class.java,
                        "ALIGN_TABLE_FIELDS",
                        LuaBundle.message("codestyle.wrapping.table.align_fields"),
                        LuaBundle.message("codestyle.wrapping.table"))
                consumer.showCustomOption(LuaCodeStyleSettings::class.java,
                        "WRAP_TABLE_FIELDS",
                        LuaBundle.message("codestyle.wrapping.table.wrap_after_comma"),
                        LuaBundle.message("codestyle.wrapping.table"))

                // Comment alignment
                consumer.showCustomOption(LuaCodeStyleSettings::class.java,
                        "ALIGN_LINE_COMMENTS",
                        LuaBundle.message("codestyle.wrapping.comment.align_line"),
                        LuaBundle.message("codestyle.wrapping.comment"))

                // Loop statement alignment
                consumer.showCustomOption(LuaCodeStyleSettings::class.java,
                        "ALIGN_LOOP_CONDITIONS",
                        LuaBundle.message("codestyle.wrapping.loop.align_body"),
                        LuaBundle.message("codestyle.wrapping.loop"))

                // Function call alignment
                consumer.showCustomOption(LuaCodeStyleSettings::class.java,
                        "ALIGN_FUNCTION_CALL_ARGUMENTS",
                        LuaBundle.message("codestyle.wrapping.function_call.align_parameters"),
                        LuaBundle.message("codestyle.wrapping.function_call"))

                // Custom variable alignment option
                val variableAlignmentOptions = arrayOf(
                    LuaCodeStyleSettings.VariableAlignmentOption.DO_NOT_ALIGN.description,
                    LuaCodeStyleSettings.VariableAlignmentOption.ALIGN_ALL.description,
                    LuaCodeStyleSettings.VariableAlignmentOption.ALIGN_CONTIGUOUS_BLOCKS.description
                )
                val variableAlignmentValues = intArrayOf(
                    LuaCodeStyleSettings.VariableAlignmentOption.DO_NOT_ALIGN.value,
                    LuaCodeStyleSettings.VariableAlignmentOption.ALIGN_ALL.value,
                    LuaCodeStyleSettings.VariableAlignmentOption.ALIGN_CONTIGUOUS_BLOCKS.value
                )
                consumer.showCustomOption(LuaCodeStyleSettings::class.java,
                        "VARIABLE_ALIGNMENT_OPTION",
                        "Variable alignment",
                        "Variable declarations",
                        variableAlignmentOptions,
                        variableAlignmentValues)
            }
            else -> {
            }
        }
    }
}

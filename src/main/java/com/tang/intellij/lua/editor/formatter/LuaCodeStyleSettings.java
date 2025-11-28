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

package com.tang.intellij.lua.editor.formatter;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;
import com.tang.intellij.lua.lang.LuaLanguage;
import com.tang.intellij.lua.LuaBundle;

/**
 * LuaCodeStyleSettings
 * Created by tangzx on 2017/2/22.
 */
public class LuaCodeStyleSettings extends CustomCodeStyleSettings {

    public boolean SPACE_AFTER_TABLE_FIELD_SEP = true;
    public boolean SPACE_AROUND_BINARY_OPERATOR = true;
    public boolean SPACE_INSIDE_INLINE_TABLE = true;

    public boolean ALIGN_TABLE_FIELD_ASSIGN = true;  // 默认勾选：对齐等号

    // 表格格式化设置
    public boolean ALIGN_TABLE_FIELDS = true;  // 默认勾选：对齐表字段
    public boolean WRAP_TABLE_FIELDS = false;
    public boolean SPACE_BETWEEN_TABLE_FIELDS = true;
    public boolean SPACE_AFTER_COMMA_IN_TABLE = true;

    // 注释对齐设置
    public boolean ALIGN_LINE_COMMENTS = true;  // 默认勾选：对齐行注释
    public boolean SPACE_BEFORE_LINE_COMMENT = true;

    // require语句设置
    public int BLANK_LINES_AFTER_REQUIRE_BLOCK = 1;

    // 函数设置
    public int BLANK_LINES_BEFORE_FUNCTION = 1;
    public int BLANK_LINES_AFTER_FUNCTION = 1;

    // 循环语句设置
    public boolean ALIGN_LOOP_CONDITIONS = false;

    // 函数调用设置
    public boolean SPACE_BEFORE_FUNCTION_CALL_PARENTHESES = false;
    public boolean SPACE_WITHIN_FUNCTION_CALL_PARENTHESES = false;
    public boolean SPACE_AFTER_COMMA_IN_FUNCTION_CALLS = true;

    /**
     * Variable alignment options
     */
    public enum VariableAlignmentOption {
        DO_NOT_ALIGN(0, LuaBundle.message("codestyle.variable.alignment.do_not_align")),
        ALIGN_ALL(1, LuaBundle.message("codestyle.variable.alignment.align_all")),
        ALIGN_CONTIGUOUS_BLOCKS(2, LuaBundle.message("codestyle.variable.alignment.align_contiguous"));

        private final int value;
        private final String description;

        VariableAlignmentOption(int value, String description) {
            this.value = value;
            this.description = description;
        }

        public int getValue() {
            return value;
        }

        public String getDescription() {
            return description;
        }

        public static VariableAlignmentOption fromValue(int value) {
            for (VariableAlignmentOption option : values()) {
                if (option.value == value) {
                    return option;
                }
            }
            return DO_NOT_ALIGN;
        }
    }

    public int VARIABLE_ALIGNMENT_OPTION = VariableAlignmentOption.ALIGN_CONTIGUOUS_BLOCKS.getValue();  // 默认：对齐连续块中的变量

    LuaCodeStyleSettings(CodeStyleSettings container) {
        super(LuaLanguage.INSTANCE.getID(), container);
    }
}

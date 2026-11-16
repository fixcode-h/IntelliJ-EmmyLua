package com.fixcode.intellij.lua.highlighting

import com.fixcode.intellij.lua.lang.LuaIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class LuaColorSettingsPage : ColorSettingsPage {
    private val h = LuaHighlightingData

    override fun getIcon(): Icon = LuaIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = LuaSyntaxHighlighter()
    override fun getDisplayName(): String = "Lua"
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Keyword", h.KEYWORD),
        AttributesDescriptor("nil / true / false", h.CONSTANT),
        AttributesDescriptor("self", h.SELF),
        AttributesDescriptor("Number", h.NUMBER),
        AttributesDescriptor("String", h.STRING),
        AttributesDescriptor("Comments//Line comment", h.LINE_COMMENT),
        AttributesDescriptor("Comments//Block comment", h.BLOCK_COMMENT),
        AttributesDescriptor("Comments//Annotation comment", h.DOC_COMMENT),
        AttributesDescriptor("Comments//Annotation tag", h.DOC_TAG),
        AttributesDescriptor("Braces and Operators//Operator", h.OPERATORS),
        AttributesDescriptor("Braces and Operators//Parentheses", h.PARENTHESES),
        AttributesDescriptor("Braces and Operators//Brackets", h.BRACKETS),
        AttributesDescriptor("Braces and Operators//Braces", h.BRACES),
        AttributesDescriptor("Braces and Operators//Dot", h.DOT),
        AttributesDescriptor("Braces and Operators//Comma", h.COMMA),
        AttributesDescriptor("Braces and Operators//Semicolon", h.SEMICOLON),
        AttributesDescriptor("Label", h.LABEL),
        AttributesDescriptor("Semantic//Local variable", h.LOCAL_VAR),
        AttributesDescriptor("Semantic//Global variable", h.GLOBAL_VAR),
        AttributesDescriptor("Semantic//Constant", h.CONSTANT_VAR),
        AttributesDescriptor("Semantic//Parameter", h.PARAMETER),
        AttributesDescriptor("Semantic//Field", h.FIELD),
        AttributesDescriptor("Semantic//Enum member", h.ENUM_VALUE),
        AttributesDescriptor("Semantic//Class name", h.CLASS_NAME),
        AttributesDescriptor("Semantic//Class reference", h.CLASS_REFERENCE),
        AttributesDescriptor("Semantic//Type alias", h.TYPE_ALIAS),
        AttributesDescriptor("Semantic//Module", h.MODULE),
        AttributesDescriptor("Semantic//Function declaration", h.FUNCTION_DECLARATION),
        AttributesDescriptor("Semantic//Method declaration", h.METHOD_DECLARATION),
        AttributesDescriptor("Semantic//Function call", h.FUNCTION_CALL),
        AttributesDescriptor("Semantic//Method call", h.METHOD_CALL),
        AttributesDescriptor("Semantic//Standard library", h.STD_API),
        AttributesDescriptor("Semantic//Annotation", h.ANNOTATION),
    )

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "local" to h.LOCAL_VAR, "global" to h.GLOBAL_VAR, "param" to h.PARAMETER, "field" to h.FIELD,
        "class" to h.CLASS_NAME, "classRef" to h.CLASS_REFERENCE, "fnDecl" to h.FUNCTION_DECLARATION,
        "methodDecl" to h.METHOD_DECLARATION, "call" to h.FUNCTION_CALL, "methodCall" to h.METHOD_CALL,
        "std" to h.STD_API, "const" to h.CONSTANT_VAR,
    )

    override fun getDemoText(): String = """
        ---@class <class>Player</class> : <classRef>Actor</classRef>
        ---@field hp number
        local <local>Player</local> = {}

        local <const>MAX_HP</const> = 100
        <global>GlobalCounter</global> = 0

        --[[ block comment ]]
        function <local>Player</local>:<methodDecl>TakeDamage</methodDecl>(<param>amount</param>)
            self.<field>hp</field> = <std>math</std>.<call>max</call>(0, self.<field>hp</field> - <param>amount</param>)
            if self.<field>hp</field> == 0 then
                self:<methodCall>Die</methodCall>()
            end
            return self.<field>hp</field> .. " / " .. <const>MAX_HP</const>, nil, true, 0x1F, [[long string]]
        end

        local function <fnDecl>tick</fnDecl>(...)
            for i = 1, #{...} do <call>print</call>(i) end
            goto done
            ::done::
        end
    """.trimIndent()
}

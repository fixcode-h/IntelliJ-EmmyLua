package com.fixcode.intellij.lua.highlighting

import com.fixcode.intellij.lua.lang.LuaIcons
import com.intellij.codeHighlighting.RainbowHighlighter
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon
import com.fixcode.intellij.lua.highlighting.LuaHighlightingData as H

/** 设置 → 编辑器 → 配色方案 → Lua。条目、分组和示例都照旧版，用户找得到原来改过的那些项。 */
class LuaColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = LuaIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = LuaSyntaxHighlighter()
    override fun getDisplayName(): String = "Lua"
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = """
        ---@class <docClassName>Emmy</docClassName>
        local <localVar>var</localVar> = {} -- a short comment
        local <localVar>a</localVar>, <localVar>b</localVar>, <localVar>c</localVar> = <primitive>true</primitive>, <primitive>false</primitive>, <primitive>nil</primitive>
        <regionHeader>--region</regionHeader> <regionDesc>my class members region</regionDesc>

        ---@alias <docTypeAlias>MyType</docTypeAlias> <docClassNameRef>Emmy</docClassNameRef>

        --- doc comment with control flow examples
        ---@param <docTagValue>par1</docTagValue> <docClassNameRef>Par1Type</docClassNameRef> @comments
        function var:<method>fun</method>(<parameter>par1</parameter>, <parameter>par2</parameter>)
           <std>print</std>('hello')
           local <functionLocalVar>funcVar</functionLocalVar> = "function scope"
           if <parameter>par1</parameter> and <parameter>par2</parameter> then
               for <localVar>i</localVar> = 1, 10 do
                   local <methodLocalVar>methodVar</methodLocalVar> = "method scope"
                   if <localVar>i</localVar> > 5 then
                       break
                   elseif <localVar>i</localVar> == 3 then
                       goto continue
                   end
                   <localVar>result</localVar> = <localVar>i</localVar> * 2 + 1
                   <localVar>bitwise</localVar> = <localVar>i</localVar> & 0xFF | 0x10
                   <localVar>concat</localVar> = "value: " .. <localVar>result</localVar>
                   <localVar>length</localVar> = #<localVar>concat</localVar>
                   ::continue::
               end
           end
           <self>self</self>.<classInstanceVar>instanceVar</classInstanceVar> = <functionLocalVar>funcVar</functionLocalVar>
           return <self>self</self>.<field>len</field> + 2
        end

        ---@overload <docKeyword>fun</docKeyword>(name:<docClassNameRef>string</docClassNameRef>):<docClassNameRef>Emmy</docClassNameRef>
        function var.<staticMethod>staticFun</staticMethod>(<parameter>...</parameter>)
           local <localVar>args</localVar> = {...}
           while <localVar>condition</localVar> do
               repeat
                   <localVar>x</localVar> = <localVar>x</localVar> + 1
               until <localVar>x</localVar> >= 10
           end
           return <localVar>args</localVar>
        end
        <regionHeader>--endregion</regionHeader> <regionDesc>end my class members region</regionDesc>

        ---@return <docClassNameRef>Emmy</docClassNameRef>
        function <globalFunction>findEmmy</globalFunction>()
           return "string" .. <upValue>var</upValue>
        end

        <globalVar>globalVar</globalVar> = {
           <field>property</field> = value,
           <tableKey>key1</tableKey> = "table value",
           <tableKey>key2</tableKey> = 123
        }

        local <constantVar>MAX_SIZE</constantVar> = 100
        local <moduleVar>_M</moduleVar> = {}

        local function <localFunction>createClosure</localFunction>()
           local <closureVar>captured</closureVar> = "captured value"
           return function()
               return <closureVar>captured</closureVar>
           end
        end

        local mt = {
           <metatableKey>__index</metatableKey> = function() end,
           <metatableKey>__newindex</metatableKey> = function() end
        }
    """.trimIndent()

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keywords//Basic Keywords", H.KEYWORD),
            AttributesDescriptor("Keywords//Control Flow Keywords", H.CONTROL_FLOW_KEYWORD),
            AttributesDescriptor("Keywords//Goto Keyword", H.GOTO_KEYWORD),
            AttributesDescriptor("self", H.SELF),
            AttributesDescriptor("String", H.STRING),
            AttributesDescriptor("nil/true/false", H.PRIMITIVE_TYPE),
            AttributesDescriptor("Number", H.NUMBER),
            AttributesDescriptor("Braces and Operators//Logical Operators", H.LOGICAL_OPERATOR),
            AttributesDescriptor("Braces and Operators//Comparison Operators", H.COMPARISON_OPERATOR),
            AttributesDescriptor("Braces and Operators//Arithmetic Operators", H.ARITHMETIC_OPERATOR),
            AttributesDescriptor("Braces and Operators//Bitwise Operators", H.BITWISE_OPERATOR),
            AttributesDescriptor("Braces and Operators//String Concatenation", H.STRING_CONCAT_OPERATOR),
            AttributesDescriptor("Braces and Operators//Length Operator", H.LENGTH_OPERATOR),
            AttributesDescriptor("Braces and Operators//Ellipsis", H.ELLIPSIS),
            AttributesDescriptor("Braces and Operators//Label", H.LABEL),
            AttributesDescriptor("Braces and Operators//Brackets", H.BRACKETS),
            AttributesDescriptor("Braces and Operators//Braces", H.BRACES),
            AttributesDescriptor("Braces and Operators//Parentheses", H.PARENTHESES),
            AttributesDescriptor("Braces and Operators//Semicolon", H.SEMICOLON),
            AttributesDescriptor("Braces and Operators//Comma", H.COMMA),
            AttributesDescriptor("Braces and Operators//Dot", H.DOT),
            AttributesDescriptor("Variables//Parameter", H.PARAMETER),
            AttributesDescriptor("Variables//Local variable", H.LOCAL_VAR),
            AttributesDescriptor("Variables//Local function", H.LOCAL_FUNCTION),
            AttributesDescriptor("Variables//Global variable", H.GLOBAL_VAR),
            AttributesDescriptor("Variables//Global function", H.GLOBAL_FUNCTION),
            AttributesDescriptor("Variables//Up value", H.UP_VALUE),
            AttributesDescriptor("Variables//Class instance variable", H.CLASS_INSTANCE_VAR),
            AttributesDescriptor("Variables//Function local variable", H.FUNCTION_LOCAL_VAR),
            AttributesDescriptor("Variables//Method local variable", H.METHOD_LOCAL_VAR),
            AttributesDescriptor("Variables//Closure variable", H.CLOSURE_VAR),
            AttributesDescriptor("Variables//Module variable", H.MODULE_VAR),
            AttributesDescriptor("Variables//Constant variable", H.CONSTANT_VAR),
            AttributesDescriptor("Variables//Table key", H.TABLE_KEY),
            AttributesDescriptor("Variables//Metatable key", H.METATABLE_KEY),
            AttributesDescriptor("Comments//Line comment", H.LINE_COMMENT),
            AttributesDescriptor("Comments//Doc comment", H.DOC_COMMENT),
            AttributesDescriptor("Comments//EmmyDoc//Tag", H.DOC_COMMENT_TAG),
            AttributesDescriptor("Comments//EmmyDoc//Tag value", H.DOC_COMMENT_TAG_VALUE),
            AttributesDescriptor("Comments//EmmyDoc//Class name", H.CLASS_NAME),
            AttributesDescriptor("Comments//EmmyDoc//Class name reference", H.CLASS_REFERENCE),
            AttributesDescriptor("Comments//EmmyDoc//Type alias", H.TYPE_ALIAS),
            AttributesDescriptor("Comments//EmmyDoc//Keyword", H.DOC_KEYWORD),
            AttributesDescriptor("Region//Region header", H.REGION_HEADER),
            AttributesDescriptor("Region//Region description", H.REGION_DESC),
            AttributesDescriptor("Class Members//Field", H.FIELD),
            AttributesDescriptor("Class Members//Instance method", H.INSTANCE_METHOD),
            AttributesDescriptor("Class Members//Static method", H.STATIC_METHOD),
            AttributesDescriptor("Functions//Function declaration", H.FUNCTION_DECLARATION),
            AttributesDescriptor("Functions//Method declaration", H.METHOD_DECLARATION),
            AttributesDescriptor("Functions//Function call", H.FUNCTION_CALL),
            AttributesDescriptor("Functions//Method call", H.METHOD_CALL),
            AttributesDescriptor("Std api", H.STD_API),
        )

        val TAGS: Map<String, TextAttributesKey> = RainbowHighlighter.createRainbowHLM() + mapOf(
            "parameter" to H.PARAMETER,
            "docTag" to H.DOC_COMMENT_TAG,
            "docTagValue" to H.DOC_COMMENT_TAG_VALUE,
            "docClassName" to H.CLASS_NAME,
            "docClassNameRef" to H.CLASS_REFERENCE,
            "docTypeAlias" to H.TYPE_ALIAS,
            "docKeyword" to H.DOC_KEYWORD,
            "localVar" to H.LOCAL_VAR,
            "localFunction" to H.LOCAL_FUNCTION,
            "globalVar" to H.GLOBAL_VAR,
            "globalFunction" to H.GLOBAL_FUNCTION,
            "field" to H.FIELD,
            "method" to H.INSTANCE_METHOD,
            "staticMethod" to H.STATIC_METHOD,
            "upValue" to H.UP_VALUE,
            "std" to H.STD_API,
            "self" to H.SELF,
            "primitive" to H.PRIMITIVE_TYPE,
            "regionHeader" to H.REGION_HEADER,
            "regionDesc" to H.REGION_DESC,
            "classInstanceVar" to H.CLASS_INSTANCE_VAR,
            "functionLocalVar" to H.FUNCTION_LOCAL_VAR,
            "methodLocalVar" to H.METHOD_LOCAL_VAR,
            "closureVar" to H.CLOSURE_VAR,
            "moduleVar" to H.MODULE_VAR,
            "constantVar" to H.CONSTANT_VAR,
            "tableKey" to H.TABLE_KEY,
            "metatableKey" to H.METATABLE_KEY,
        )
    }
}

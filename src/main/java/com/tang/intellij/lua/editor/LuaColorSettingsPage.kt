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

package com.tang.intellij.lua.editor

import com.intellij.codeHighlighting.RainbowHighlighter
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.tang.intellij.lua.highlighting.LuaHighlightingData
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.lang.LuaLanguage
import org.jetbrains.annotations.NonNls
import javax.swing.Icon

/**
 * Color Settings Page
 * Created by TangZX on 2017/1/9.
 */
class LuaColorSettingsPage : ColorSettingsPage {

    override fun getIcon(): Icon {
        return LuaIcons.FILE
    }

    override fun getHighlighter(): SyntaxHighlighter {
        return SyntaxHighlighterFactory.getSyntaxHighlighter(LuaLanguage.INSTANCE, null, null)
    }

    override fun getDemoText(): String {
        return """
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
    }

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> {
        return ourTags
    }

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> {
        return ourAttributeDescriptors
    }

    override fun getColorDescriptors(): Array<ColorDescriptor> {
        return emptyArray()
    }

    override fun getDisplayName(): String {
        return "Lua"
    }

    companion object {
        private val ourAttributeDescriptors = arrayOf(
                AttributesDescriptor("Keywords//Basic Keywords", LuaHighlightingData.KEYWORD),
                AttributesDescriptor("Keywords//Control Flow Keywords", LuaHighlightingData.CONTROL_FLOW_KEYWORD),
                AttributesDescriptor("Keywords//Goto Keyword", LuaHighlightingData.GOTO_KEYWORD),
                AttributesDescriptor("self", LuaHighlightingData.SELF),
                AttributesDescriptor("String", LuaHighlightingData.STRING),
                AttributesDescriptor("nil/true/false", LuaHighlightingData.PRIMITIVE_TYPE),
                AttributesDescriptor("Number", LuaHighlightingData.NUMBER),
                AttributesDescriptor("Braces and Operators//Logical Operators", LuaHighlightingData.LOGICAL_OPERATOR),
                AttributesDescriptor("Braces and Operators//Comparison Operators", LuaHighlightingData.COMPARISON_OPERATOR),
                AttributesDescriptor("Braces and Operators//Arithmetic Operators", LuaHighlightingData.ARITHMETIC_OPERATOR),
                AttributesDescriptor("Braces and Operators//Bitwise Operators", LuaHighlightingData.BITWISE_OPERATOR),
                AttributesDescriptor("Braces and Operators//String Concatenation", LuaHighlightingData.STRING_CONCAT_OPERATOR),
                AttributesDescriptor("Braces and Operators//Length Operator", LuaHighlightingData.LENGTH_OPERATOR),
                AttributesDescriptor("Braces and Operators//Ellipsis", LuaHighlightingData.ELLIPSIS),
                AttributesDescriptor("Braces and Operators//Label", LuaHighlightingData.LABEL),
                AttributesDescriptor("Braces and Operators//Brackets", LuaHighlightingData.BRACKETS),
                AttributesDescriptor("Braces and Operators//Braces", LuaHighlightingData.BRACES),
                AttributesDescriptor("Braces and Operators//Parentheses", LuaHighlightingData.PARENTHESES),
                AttributesDescriptor("Braces and Operators//Semicolon", LuaHighlightingData.SEMICOLON),
                AttributesDescriptor("Braces and Operators//Comma", LuaHighlightingData.COMMA),
                AttributesDescriptor("Braces and Operators//Dot", LuaHighlightingData.DOT),
                AttributesDescriptor("Variables//Parameter", LuaHighlightingData.PARAMETER),
                AttributesDescriptor("Variables//Local variable", LuaHighlightingData.LOCAL_VAR),
                AttributesDescriptor("Variables//Local function", LuaHighlightingData.LOCAL_FUNCTION),
                AttributesDescriptor("Variables//Global variable", LuaHighlightingData.GLOBAL_VAR),
                AttributesDescriptor("Variables//Global function", LuaHighlightingData.GLOBAL_FUNCTION),
                AttributesDescriptor("Variables//Up value", LuaHighlightingData.UP_VALUE),
                AttributesDescriptor("Variables//Class instance variable", LuaHighlightingData.CLASS_INSTANCE_VAR),
                AttributesDescriptor("Variables//Function local variable", LuaHighlightingData.FUNCTION_LOCAL_VAR),
                AttributesDescriptor("Variables//Method local variable", LuaHighlightingData.METHOD_LOCAL_VAR),
                AttributesDescriptor("Variables//Closure variable", LuaHighlightingData.CLOSURE_VAR),
                AttributesDescriptor("Variables//Module variable", LuaHighlightingData.MODULE_VAR),
                AttributesDescriptor("Variables//Constant variable", LuaHighlightingData.CONSTANT_VAR),
                AttributesDescriptor("Variables//Table key", LuaHighlightingData.TABLE_KEY),
                AttributesDescriptor("Variables//Metatable key", LuaHighlightingData.METATABLE_KEY),
                AttributesDescriptor("Comments//Line comment", LuaHighlightingData.LINE_COMMENT),
                AttributesDescriptor("Comments//Doc comment", LuaHighlightingData.DOC_COMMENT),
                AttributesDescriptor("Comments//EmmyDoc//Tag", LuaHighlightingData.DOC_COMMENT_TAG),
                AttributesDescriptor("Comments//EmmyDoc//Tag value", LuaHighlightingData.DOC_COMMENT_TAG_VALUE),
                AttributesDescriptor("Comments//EmmyDoc//Class name", LuaHighlightingData.CLASS_NAME),
                AttributesDescriptor("Comments//EmmyDoc//Class name reference", LuaHighlightingData.CLASS_REFERENCE),
                AttributesDescriptor("Comments//EmmyDoc//Type alias", LuaHighlightingData.TYPE_ALIAS),
                AttributesDescriptor("Comments//EmmyDoc//Keyword", LuaHighlightingData.DOC_KEYWORD),
                AttributesDescriptor("Region//Region header", LuaHighlightingData.REGION_HEADER),
                AttributesDescriptor("Region//Region description", LuaHighlightingData.REGION_DESC),
                AttributesDescriptor("Class Members//Field", LuaHighlightingData.FIELD),
                AttributesDescriptor("Class Members//Instance method", LuaHighlightingData.INSTANCE_METHOD),
                AttributesDescriptor("Class Members//Static method", LuaHighlightingData.STATIC_METHOD),
                AttributesDescriptor("Functions//Function declaration", LuaHighlightingData.FUNCTION_DECLARATION),
                AttributesDescriptor("Functions//Method declaration", LuaHighlightingData.METHOD_DECLARATION),
                AttributesDescriptor("Functions//Function call", LuaHighlightingData.FUNCTION_CALL),
                AttributesDescriptor("Functions//Method call", LuaHighlightingData.METHOD_CALL),
                AttributesDescriptor("Std api", LuaHighlightingData.STD_API))

        @NonNls
        private val ourTags: MutableMap<String, TextAttributesKey> = RainbowHighlighter.createRainbowHLM()

        init {
            ourTags["parameter"] = LuaHighlightingData.PARAMETER
            ourTags["docTag"] = LuaHighlightingData.DOC_COMMENT_TAG
            ourTags["docTagValue"] = LuaHighlightingData.DOC_COMMENT_TAG_VALUE
            ourTags["docClassName"] = LuaHighlightingData.CLASS_NAME
            ourTags["docClassNameRef"] = LuaHighlightingData.CLASS_REFERENCE
            ourTags["docTypeAlias"] = LuaHighlightingData.TYPE_ALIAS
            ourTags["docKeyword"] = LuaHighlightingData.DOC_KEYWORD
            ourTags["localVar"] = LuaHighlightingData.LOCAL_VAR
            ourTags["localFunction"] = LuaHighlightingData.LOCAL_FUNCTION
            ourTags["globalVar"] = LuaHighlightingData.GLOBAL_VAR
            ourTags["globalFunction"] = LuaHighlightingData.GLOBAL_FUNCTION
            ourTags["field"] = LuaHighlightingData.FIELD
            ourTags["method"] = LuaHighlightingData.INSTANCE_METHOD
            ourTags["staticMethod"] = LuaHighlightingData.STATIC_METHOD
            ourTags["upValue"] = LuaHighlightingData.UP_VALUE
            ourTags["std"] = LuaHighlightingData.STD_API
            ourTags["self"] = LuaHighlightingData.SELF
            ourTags["primitive"] = LuaHighlightingData.PRIMITIVE_TYPE
            ourTags["regionHeader"] = LuaHighlightingData.REGION_HEADER
            ourTags["regionDesc"] = LuaHighlightingData.REGION_DESC
            ourTags["classInstanceVar"] = LuaHighlightingData.CLASS_INSTANCE_VAR
            ourTags["functionLocalVar"] = LuaHighlightingData.FUNCTION_LOCAL_VAR
            ourTags["methodLocalVar"] = LuaHighlightingData.METHOD_LOCAL_VAR
            ourTags["closureVar"] = LuaHighlightingData.CLOSURE_VAR
            ourTags["moduleVar"] = LuaHighlightingData.MODULE_VAR
            ourTags["constantVar"] = LuaHighlightingData.CONSTANT_VAR
            ourTags["tableKey"] = LuaHighlightingData.TABLE_KEY
            ourTags["metatableKey"] = LuaHighlightingData.METATABLE_KEY
        }
    }
}

package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.highlighting.LuaHighlightingData as H
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.features.semanticTokens.SemanticTokensColorsProvider

/**
 * emmylua_ls 语义 token → 旧版的 Lua 配色键，颜色和旧版（PSI 版）对得上。
 *
 * 按 emmylua_ls 0.25 实际发的 token 定（`readonly` 表示没被重新赋值，不是常量；`static` 表示全局；
 * 代码里的 `class` 是「值是一个类的变量」，注解里的类型引用发 `type`）：
 * - 关键字、运算符、括号、注释、字符串、数字返回 null，留给词法高亮：旧版把关键字分控制流 / 逻辑 / goto，
 *   语义 token 分不出来，盖上去反而把颜色抹平。
 * - `self` 发成 `variable` + `definition`，返回 null 让词法的 `LUA_SELF` 生效。
 */
class EmmyLuaSemanticTokensColors : SemanticTokensColorsProvider {
    override fun getTextAttributesKey(tokenType: String, tokenModifiers: List<String>, file: PsiFile): TextAttributesKey? {
        fun has(m: String) = m in tokenModifiers
        val declaration = has("declaration")
        return when (tokenType) {
            "keyword" -> if (has("documentation")) H.DOC_COMMENT_TAG else null
            "class", "enum", "interface", "struct" -> when {
                declaration -> H.CLASS_NAME // ---@class Foo
                has("defaultLibrary") -> H.STD_API
                has("static") -> H.GLOBAL_VAR // 全局表：UE、string、math……
                else -> H.LOCAL_VAR // local Player = {}
            }
            "type" -> if (declaration) H.TYPE_ALIAS else H.CLASS_REFERENCE
            "typeParameter" -> H.CLASS_REFERENCE
            "namespace" -> H.MODULE_VAR
            "parameter" -> if (declaration) H.DOC_COMMENT_TAG_VALUE else H.PARAMETER // ---@param 里的参数名
            "variable" -> when {
                has("definition") -> null // self
                has("defaultLibrary") -> H.STD_API
                has("static") -> H.GLOBAL_VAR
                else -> H.LOCAL_VAR
            }
            "property" -> H.FIELD
            "enumMember" -> if (declaration) H.FIELD else H.ENUM_VALUE
            "event" -> H.FIELD
            "function" -> when {
                has("defaultLibrary") -> H.STD_API
                declaration -> H.FUNCTION_DECLARATION
                else -> H.FUNCTION_CALL
            }
            "method" -> when {
                has("defaultLibrary") -> H.STD_API
                declaration -> H.METHOD_DECLARATION
                else -> H.METHOD_CALL
            }
            "decorator", "macro" -> H.DOC_COMMENT_TAG
            else -> null
        }
    }
}

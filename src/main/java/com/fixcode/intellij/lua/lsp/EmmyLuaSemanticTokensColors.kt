package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.highlighting.LuaHighlightingData as H
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.features.semanticTokens.SemanticTokensColorsProvider

/**
 * emmylua_ls 语义 token → Lua 配色键，用户在「配色方案 → Lua」里改的颜色对语义高亮也生效。
 * 注释、字符串、数字、运算符返回 null，留给词法高亮（它们本来就准，且打字时不闪）。
 */
class EmmyLuaSemanticTokensColors : SemanticTokensColorsProvider {
    override fun getTextAttributesKey(tokenType: String, tokenModifiers: List<String>, file: PsiFile): TextAttributesKey? {
        fun has(m: String) = m in tokenModifiers
        val declaration = has("declaration") || has("definition")
        return when (tokenType) {
            "keyword" -> when {
                has("documentation") -> H.DOC_TAG
                else -> H.KEYWORD
            }
            "class", "enum", "interface", "struct" -> if (declaration) H.CLASS_NAME else H.CLASS_REFERENCE
            "type" -> when {
                declaration -> H.TYPE_ALIAS
                else -> H.CLASS_REFERENCE
            }
            "typeParameter" -> H.CLASS_REFERENCE
            "namespace" -> H.MODULE
            "parameter" -> H.PARAMETER
            "variable" -> when {
                has("defaultLibrary") -> H.STD_API
                has("readonly") -> H.CONSTANT_VAR
                has("static") -> H.GLOBAL_VAR
                else -> H.LOCAL_VAR
            }
            "property" -> H.FIELD
            "enumMember" -> H.ENUM_VALUE
            "function" -> when {
                has("defaultLibrary") -> H.STD_API
                declaration -> H.FUNCTION_DECLARATION
                else -> H.FUNCTION_CALL
            }
            "method" -> if (declaration) H.METHOD_DECLARATION else H.METHOD_CALL
            "decorator", "macro" -> H.ANNOTATION
            "event" -> H.FIELD
            else -> null
        }
    }
}

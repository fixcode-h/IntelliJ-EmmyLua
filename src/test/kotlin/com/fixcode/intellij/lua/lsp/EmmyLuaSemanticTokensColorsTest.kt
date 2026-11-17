package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.highlighting.LuaHighlightingData as H
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** 用 emmylua_ls 0.25 对真实代码发出的 token 和修饰符（见类注释）核对映射到旧版配色键。 */
class EmmyLuaSemanticTokensColorsTest {
    private val file = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PsiFile::class.java)) { _, _, _ -> null } as PsiFile
    private val colors = EmmyLuaSemanticTokensColors()
    private fun key(type: String, vararg mods: String): TextAttributesKey? = colors.getTextAttributesKey(type, mods.toList(), file)

    @Test
    fun variables() {
        assertEquals(H.LOCAL_VAR, key("variable", "readonly")) // local count = 0
        assertEquals(H.LOCAL_VAR, key("variable")) // 被重新赋值的局部变量
        assertEquals(H.GLOBAL_VAR, key("variable", "readonly", "static")) // GlobalVar = 10
        assertEquals(null, key("variable", "definition")) // self，交给词法
        assertEquals(H.LOCAL_VAR, key("class", "readonly")) // local Player = {}
        assertEquals(H.GLOBAL_VAR, key("class", "static")) // string、math、UE
        assertEquals(H.PARAMETER, key("parameter", "readonly"))
    }

    @Test
    fun functionsAndMembers() {
        assertEquals(H.FUNCTION_DECLARATION, key("function", "declaration"))
        assertEquals(H.FUNCTION_CALL, key("function"))
        assertEquals(H.STD_API, key("function", "defaultLibrary")) // print
        assertEquals(H.METHOD_DECLARATION, key("method", "declaration"))
        assertEquals(H.METHOD_CALL, key("method"))
        assertEquals(H.FIELD, key("property"))
        assertEquals(H.ENUM_VALUE, key("enumMember", "readonly"))
    }

    @Test
    fun annotationsAndLexicalTokens() {
        assertEquals(H.CLASS_NAME, key("class", "declaration"))
        assertEquals(H.CLASS_REFERENCE, key("type"))
        assertEquals(H.TYPE_ALIAS, key("type", "declaration"))
        assertEquals(H.DOC_COMMENT_TAG, key("keyword", "documentation"))
        assertEquals(H.DOC_COMMENT_TAG_VALUE, key("parameter", "declaration"))
        assertEquals(null, key("keyword")) // 关键字按旧版分类，留给词法
        assertEquals(null, key("operator"))
        assertEquals(null, key("comment"))
    }
}

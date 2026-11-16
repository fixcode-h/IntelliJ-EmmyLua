package com.fixcode.intellij.lua.lang

import com.fixcode.intellij.lua.lexer.LuaLexer
import com.fixcode.intellij.lua.lexer.LuaTokenTypes
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class LuaParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = LuaLexer()

    /** 不做语法分析：把所有 token 平铺到文件根下，代价等于一次词法扫描。 */
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val mark = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        mark.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = LuaTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = LuaTokenTypes.STRINGS
    override fun getWhitespaceTokens(): TokenSet = LuaTokenTypes.WHITESPACES
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = LuaPsiFile(viewProvider)

    companion object {
        @JvmField
        val FILE = IFileElementType("LUA_FILE", LuaLanguage)
    }
}

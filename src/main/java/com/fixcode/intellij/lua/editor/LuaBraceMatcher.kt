package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class LuaBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(T.LPAREN, T.RPAREN, false),
            BracePair(T.LBRACK, T.RBRACK, false),
            BracePair(T.LCURLY, T.RCURLY, true),
        )
    }
}

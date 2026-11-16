package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lexer.LuaTokenTypes
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler

class LuaQuoteHandler : SimpleTokenSetQuoteHandler(LuaTokenTypes.STRING)

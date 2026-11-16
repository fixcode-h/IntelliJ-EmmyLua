package com.fixcode.intellij.lua.lang

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object LuaIcons {
    private fun load(path: String): Icon = IconLoader.getIcon(path, LuaIcons::class.java)

    @JvmField val FILE: Icon = load("/icons/lua.svg")

    object Debugger {
        @JvmField val Console: Icon = load("/icons/console.svg")
        @JvmField val StackFrame: Icon = load("/icons/frame.svg")
    }
}

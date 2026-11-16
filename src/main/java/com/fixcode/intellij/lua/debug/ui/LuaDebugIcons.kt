package com.fixcode.intellij.lua.debug.ui

import com.intellij.icons.AllIcons
import com.fixcode.intellij.lua.lang.LuaIcons
import javax.swing.Icon

object LuaDebugIcons {
    val LUA: Icon get() = LuaIcons.FILE
    val ATTACH: Icon get() = AllIcons.Debugger.AttachToProcess
    val DEVICE: Icon get() = AllIcons.Nodes.Deploy
    val MCP_RUNNING: Icon get() = AllIcons.General.InspectionsOK
    val MCP_STOPPED: Icon get() = AllIcons.General.InspectionsPause
    val MCP_ERROR: Icon get() = AllIcons.General.Error
    val MCP_NOT_INSTALLED: Icon get() = AllIcons.General.Warning
}

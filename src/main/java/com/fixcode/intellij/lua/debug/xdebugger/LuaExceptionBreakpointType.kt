package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import com.fixcode.intellij.lua.debug.protocol.ExceptionModes
import javax.swing.JComponent

/** 「Lua 出错时暂停」的设置：只停没被 pcall 接住的，还是连被接住的也停。 */
class LuaExceptionBreakpointProperties : XBreakpointProperties<LuaExceptionBreakpointProperties>() {
    @Attribute("includeCaught")
    var includeCaught: Boolean = false

    override fun getState(): LuaExceptionBreakpointProperties = this

    override fun loadState(state: LuaExceptionBreakpointProperties) {
        includeCaught = state.includeCaught
    }

    /** 对应 setOptions.exceptions（协议 11 节）。 */
    val mode: String get() = if (includeCaught) ExceptionModes.ALL else ExceptionModes.UNCAUGHT
}

/**
 * 出错即停，放在「断点」对话框里（和 Java 的「任何异常」一样），默认关闭：
 * 很多游戏每帧都有被 pcall 接住的错误，默认打开会一直停。
 */
class LuaExceptionBreakpointType :
    XBreakpointType<XBreakpoint<LuaExceptionBreakpointProperties>, LuaExceptionBreakpointProperties>(ID, "Lua 错误") {

    override fun getDisplayText(breakpoint: XBreakpoint<LuaExceptionBreakpointProperties>): String =
        if (breakpoint.properties?.includeCaught == true) "Lua 出错时暂停（包括被 pcall 接住的）" else "Lua 出错时暂停（未被 pcall 接住的）"

    override fun createProperties(): LuaExceptionBreakpointProperties = LuaExceptionBreakpointProperties()

    override fun createDefaultBreakpoint(creator: XBreakpointCreator<LuaExceptionBreakpointProperties>): XBreakpoint<LuaExceptionBreakpointProperties> =
        creator.createBreakpoint(LuaExceptionBreakpointProperties()).also { it.isEnabled = false }

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<XBreakpoint<LuaExceptionBreakpointProperties>> =
        object : XBreakpointCustomPropertiesPanel<XBreakpoint<LuaExceptionBreakpointProperties>>() {
            private val caught = JBCheckBox("也在被 pcall / xpcall 接住的错误处暂停")

            override fun getComponent(): JComponent = caught

            override fun saveTo(breakpoint: XBreakpoint<LuaExceptionBreakpointProperties>) {
                breakpoint.properties.includeCaught = caught.isSelected
            }

            override fun loadFrom(breakpoint: XBreakpoint<LuaExceptionBreakpointProperties>) {
                caught.isSelected = breakpoint.properties.includeCaught
            }
        }

    companion object {
        const val ID = "emmylua-exception"
    }
}

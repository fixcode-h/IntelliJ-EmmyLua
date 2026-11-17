package com.fixcode.intellij.lua.debug.xdebugger

import com.fixcode.intellij.lua.debugger.LuaDebuggerEditorsProvider
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import javax.swing.JComponent

/** 函数断点的设置：函数名（`Module.func` / `Class:Method` / 全局函数名），可选限定文件。 */
class LuaFunctionBreakpointProperties : XBreakpointProperties<LuaFunctionBreakpointProperties>() {
    @Attribute("function")
    var function: String = ""

    /** 只认这个文件里的函数；空表示不限。 */
    @Attribute("file")
    var file: String = ""

    override fun getState(): LuaFunctionBreakpointProperties = this

    override fun loadState(state: LuaFunctionBreakpointProperties) {
        function = state.function
        file = state.file
    }
}

/**
 * 函数断点（协议 7.7）：不知道代码在哪个文件时，按函数名下断点，函数被调用时停在它的第一行。
 * 在「断点」对话框里用 + 新建；条件、日志、「不挂起」和行断点一样由 runtime 处理。
 * 类型 ID 是持久化名字，定了不改。
 */
class LuaFunctionBreakpointType :
    XBreakpointType<XBreakpoint<LuaFunctionBreakpointProperties>, LuaFunctionBreakpointProperties>(ID, "Lua 函数断点") {

    override fun getDisplayText(breakpoint: XBreakpoint<LuaFunctionBreakpointProperties>): String {
        val props = breakpoint.properties ?: return "Lua 函数"
        val file = props.file.takeIf { it.isNotBlank() }?.let { "（${it.substringAfterLast('/')}）" } ?: ""
        return "函数 ${props.function.ifBlank { "?" }}$file"
    }

    override fun createProperties(): LuaFunctionBreakpointProperties = LuaFunctionBreakpointProperties()

    override fun isAddBreakpointButtonVisible(): Boolean = true

    override fun addBreakpoint(project: Project, parentComponent: JComponent?): XBreakpoint<LuaFunctionBreakpointProperties>? {
        val name = Messages.showInputDialog(
            project,
            "函数名：Module.func、Class:Method 或全局函数名。函数被调用时停在它的第一行。",
            "添加 Lua 函数断点",
            null,
            "",
            NameValidator
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val props = LuaFunctionBreakpointProperties().also { it.function = name }
        return WriteAction.compute<XBreakpoint<LuaFunctionBreakpointProperties>, RuntimeException> {
            XDebuggerManager.getInstance(project).breakpointManager.addBreakpoint(this, props)
        }
    }

    // 条件、日志表达式的编辑器和行断点一样
    override fun getEditorsProvider(breakpoint: XBreakpoint<LuaFunctionBreakpointProperties>, project: Project): XDebuggerEditorsProvider =
        LuaDebuggerEditorsProvider()

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<XBreakpoint<LuaFunctionBreakpointProperties>> =
        object : XBreakpointCustomPropertiesPanel<XBreakpoint<LuaFunctionBreakpointProperties>>() {
            private val name = JBTextField()
            private val file = JBTextField().also { it.emptyText.text = "不限（任意文件）" }
            private val panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("函数名：", name)
                .addLabeledComponent("只在文件：", file)
                .panel

            override fun getComponent(): JComponent = panel

            override fun saveTo(breakpoint: XBreakpoint<LuaFunctionBreakpointProperties>) {
                val text = name.text.trim()
                // 写法不对时保留原来的名字，不让断点变成空的
                if (NameValidator.getErrorText(text) == null) breakpoint.properties.function = text
                breakpoint.properties.file = file.text.trim()
            }

            override fun loadFrom(breakpoint: XBreakpoint<LuaFunctionBreakpointProperties>) {
                name.text = breakpoint.properties.function
                file.text = breakpoint.properties.file
            }
        }

    /** 写法和 runtime 一致：段名是 Lua 标识符，点分，最后一段前可以是 ':'。 */
    private object NameValidator : InputValidatorEx {
        private val pattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*(:[A-Za-z_][A-Za-z0-9_]*)?")

        override fun getErrorText(inputString: String): String? =
            if (pattern.matches(inputString.trim())) null else "写成 Module.func、Class:Method 或全局函数名"

        override fun checkInput(inputString: String): Boolean = getErrorText(inputString) == null

        override fun canClose(inputString: String): Boolean = checkInput(inputString)
    }

    companion object {
        const val ID = "emmylua-function"
    }
}

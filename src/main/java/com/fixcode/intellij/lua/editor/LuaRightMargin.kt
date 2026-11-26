package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lang.LuaFileType
import com.fixcode.intellij.lua.project.LuaSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * Lua 编辑器里的右边距参考线（「显示硬换行和视觉参考线」画的那条竖线）。
 *
 * IDE 默认在第 120 列给所有语言画这条线，Lua 项目里行普遍偏长，这条线横在代码中间只是干扰，
 * 所以插件默认只在 Lua 编辑器里关掉它；设置 → EmmyLua 里可以打开（打开后按 IDE 的全局开关显示）。
 * 只改单个编辑器的设置，不动 IDE 全局和其他语言。
 */
class LuaRightMargin : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        apply(event.editor)
    }

    companion object {
        /** 设置改了以后刷新已打开的 Lua 编辑器。 */
        fun refreshAll() {
            EditorFactory.getInstance().allEditors.forEach(::apply)
        }

        private fun apply(editor: Editor) {
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
            if (file.fileType != LuaFileType) return
            val show = LuaSettings.instance.showRightMargin && EditorSettingsExternalizable.getInstance().isRightMarginShown
            if (editor.settings.isRightMarginShown != show) editor.settings.isRightMarginShown = show
        }
    }
}

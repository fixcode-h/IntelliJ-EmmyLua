package com.fixcode.intellij.lua.project

import com.intellij.ide.DataManager
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import java.awt.Component

/**
 * 语言（语言和框架 → EmmyLua）和调试（工具 → EmmyLua 调试）分在两处设置页，互相加个链接。
 * 已经在设置对话框里时直接切过去；不在（比如从别的入口打开的单独页面）就新开设置对话框。
 */
object SettingsJump {
    const val LANGUAGE_ID = "preferences.Lua"
    const val DEBUG_ID = "emmylua.debug"

    fun open(source: Component?, configurableId: String, displayName: String) {
        val settings = source?.let { Settings.KEY.getData(DataManager.getInstance().getDataContext(it)) }
        val target = settings?.find(configurableId)
        if (settings != null && target != null) {
            settings.select(target)
            return
        }
        ShowSettingsUtil.getInstance().showSettingsDialog(ProjectUtil.getActiveProject(), displayName)
    }
}

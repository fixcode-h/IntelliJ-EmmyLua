package com.fixcode.intellij.lua.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 应用级设置。存储名和字段名沿用旧版（`LuaSettings` / `emmy.xml`），升级后用户的文件模板、Lua 版本不丢；
 * 旧版里只给 PSI 推断用的字段删掉了，XML 里残留的旧字段读的时候忽略。
 */
@Service(Service.Level.APP)
@State(name = "LuaSettings", storages = [Storage("emmy.xml")])
class LuaSettings : PersistentStateComponent<LuaSettings> {
    /** 默认 Lua 版本。只在工作区没有 `.emmyrc.json` / `.luarc.json` 时下发给 emmylua_ls。 */
    var languageLevel: LuaLanguageLevel = LuaLanguageLevel.LUA54

    /** emmylua_ls 可执行文件；留空用插件自带的。 */
    var languageServerPath: String = ""

    /**
     * Ctrl+Alt+L 时主动对齐表字段的 `=`（旧版的效果），项目里没有 `.luafmt.toml` 时按旧版默认格式化。
     * 关掉就完全交给 emmylua_ls（只在源码里已经对齐过的表才对齐）。见 format/LuaFormattingService。
     */
    var alignTableFields: Boolean = true

    var enableCustomFileTemplate: Boolean = true
    var customFileTemplate: String = DEFAULT_FILE_TEMPLATE
    var enableFileNameReplacement: Boolean = true
    var fileNamePlaceholder: String = "\${FILE_NAME}"

    override fun getState(): LuaSettings = this

    override fun loadState(state: LuaSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        const val DEFAULT_FILE_TEMPLATE =
            "---\n--- Created by \${USER}.\n--- DateTime: \${DATE} \${TIME}\n---\n\n---@class \${FILE_NAME}\nlocal \${FILE_NAME} = {}\n\nreturn \${FILE_NAME}"

        @JvmStatic
        val instance: LuaSettings
            get() = ApplicationManager.getApplication().getService(LuaSettings::class.java)
    }
}

/** 枚举名沿用旧版（存在 emmy.xml 里）；[emmyrcName] 是 `.emmyrc.json` 里 `runtime.version` 的写法。 */
enum class LuaLanguageLevel(val emmyrcName: String, private val display: String) {
    LUA50("Lua5.1", "Lua 5.0"),
    LUA51("Lua5.1", "Lua 5.1"),
    LUAJIT("LuaJIT", "LuaJIT"),
    LUA52("Lua5.2", "Lua 5.2"),
    LUA53("Lua5.3", "Lua 5.3"),
    LUA54("Lua5.4", "Lua 5.4"),
    LUA55("Lua5.5", "Lua 5.5");

    override fun toString(): String = display
}

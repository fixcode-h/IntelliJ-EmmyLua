package com.fixcode.intellij.lua.project

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 项目级设置。`additionalSourcesRoot` 沿用旧版字段名（`emmyLua.xml`），旧版的「额外源码根」升级后变成 emmylua_ls 的库目录。
 */
@Service(Service.Level.PROJECT)
@State(name = "LuaProjectSettings", storages = [Storage("emmyLua.xml")])
class LuaProjectSettings : PersistentStateComponent<LuaProjectSettings> {
    var additionalSourcesRoot: Array<String> = emptyArray()

    /** 往上找 `.uproject`，把 UnLua 生成的 IntelliSense 目录加进库。 */
    var detectUnrealProject: Boolean = true

    /** 让 UE 编辑器（UELink 2.5+）后台生成 UE 反射的 Lua 声明；默认关（用户 2026-11-16 要求不默认生成）。 */
    var unrealLuaDecl: Boolean = false

    /** 上面打开时，蓝图（及用户定义结构体 / 枚举）也生成声明（UELink 2.7+ 的 `blueprints`）；默认关，只出 C++ 反射类型（用户 2026-11-17 定）。 */
    var unrealLuaDeclBlueprints: Boolean = false

    override fun getState(): LuaProjectSettings = this

    override fun loadState(state: LuaProjectSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): LuaProjectSettings = project.getService(LuaProjectSettings::class.java)
    }
}

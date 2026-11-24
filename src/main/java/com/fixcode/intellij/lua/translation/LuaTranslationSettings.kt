package com.fixcode.intellij.lua.translation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** 注释翻译的设置（应用级，所有项目共用，和缓存一样）。设置页在「语言和框架 → EmmyLua → 注释翻译」。 */
@Service(Service.Level.APP)
@State(name = "LuaTranslationSettings", storages = [Storage("emmylua-translation.xml")])
class LuaTranslationSettings : PersistentStateComponent<LuaTranslationSettings> {
    /** 悬停文档里的注释在后台翻译。 */
    var enabled: Boolean = true
    var display: TranslationDisplay = TranslationDisplay.BILINGUAL
    var provider: TranslationProviderKind = TranslationProviderKind.GOOGLE

    /** `auto` 或 zh-CN / zh-TW / en / ja / ko。 */
    var target: String = TranslationText.AUTO_TARGET

    /** 谷歌翻译的地址；留空用 translate.googleapis.com。 */
    var googleUrl: String = ""

    override fun getState(): LuaTranslationSettings = this

    override fun loadState(state: LuaTranslationSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        val instance: LuaTranslationSettings
            get() = ApplicationManager.getApplication().getService(LuaTranslationSettings::class.java)
    }
}

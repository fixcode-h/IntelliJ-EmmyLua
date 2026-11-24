package com.fixcode.intellij.lua.translation

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JLabel

/** 设置 → 语言和框架 → EmmyLua → 注释翻译（照 UEToVS 设置里的「注释翻译」页）。 */
class LuaTranslationConfigurable : BoundSearchableConfigurable("注释翻译", "preferences.Lua.translation", ID) {
    private val s = LuaTranslationSettings.instance
    private var target = TargetOption.of(s.target)
    private var cacheLabel: JLabel? = null

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("翻译悬停文档里的注释").bindSelected(s::enabled)
                .comment(
                    "悬停文档里的英文注释在后台翻译，先显示原文，译文到了再补上，不阻塞悬停；翻译过的内容缓存在本机（IDE 系统目录下的 " +
                        "emmylua/translation），下次直接显示。注释原文会发给下面选的翻译服务。编辑器右键「翻译选中内容」不受这个开关影响"
                )
        }
        row("显示方式：") {
            comboBox(TranslationDisplay.entries).bindItem({ s.display }, { s.display = it ?: TranslationDisplay.BILINGUAL })
                .comment("双语对照：每段原文下面跟着译文；只显示译文：译文到了以后替换原文（翻译失败时仍显示原文）。「翻译选中内容」也按这个显示")
        }
        row("翻译服务：") {
            comboBox(TranslationProviderKind.entries).bindItem({ s.provider }, { s.provider = it ?: TranslationProviderKind.GOOGLE })
                .comment(
                    "都是免费接口，不需要密钥。谷歌翻译质量最好，但需要能访问 translate.googleapis.com（可在下面换成镜像地址，或在「HTTP 代理」里配代理）；" +
                        "微软翻译用的是 Edge 网页翻译的接口，国内通常可以直接访问；MyMemory 匿名使用每天约 5000 词"
                )
        }
        row("翻译成：") {
            comboBox(TargetOption.ALL).bindItem({ target }, { target = it ?: TargetOption.ALL.first() })
                .comment("自动：IDE 或系统是中文时翻译成简体中文，否则翻译成英文（只翻译中文注释）")
        }
        row("谷歌翻译服务地址：") {
            textField().bindText(s::googleUrl).align(AlignX.FILL)
                .comment("留空使用 ${GoogleTranslateProvider.DEFAULT_BASE_URL}；可以填一个兼容的镜像地址")
        }
        row("翻译缓存：") {
            button("清空") {
                val n = ClearTranslationCacheAction.clearCache()
                cacheLabel?.text = "已清空（$n 条）"
            }.comment("删除本机缓存的译文（换了翻译服务或觉得译文不对时用）")
            cacheLabel = label("").component
        }
    }

    override fun apply() {
        super.apply()
        s.target = target.code
        // 设置变了：之前的失败（比如谷歌连不上）不再挡着新的请求
        LuaTranslation.service.resetCooldown()
    }

    override fun isModified(): Boolean = super.isModified() || target.code != s.target

    override fun reset() {
        target = TargetOption.of(s.target)
        super.reset()
    }

    data class TargetOption(val code: String, val label: String) {
        override fun toString(): String = label

        companion object {
            val ALL = listOf(
                TargetOption(TranslationText.AUTO_TARGET, "自动（跟随界面语言）"),
                TargetOption("zh-CN", "简体中文"),
                TargetOption("zh-TW", "繁體中文"),
                TargetOption("en", "English"),
                TargetOption("ja", "日本語"),
                TargetOption("ko", "한국어"),
            )

            fun of(code: String?): TargetOption = ALL.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: ALL.first()
        }
    }

    companion object {
        const val ID = "preferences.Lua.translation"
    }
}

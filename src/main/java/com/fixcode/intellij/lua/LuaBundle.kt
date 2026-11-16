package com.fixcode.intellij.lua

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

@NonNls
private const val BUNDLE = "messages.LuaBundle"

/** 语言部分的文案。系统语言是中文时直接用中文，不要求装 IDE 中文语言包。 */
object LuaBundle {
    private val instance = DynamicBundle(LuaBundle::class.java, BUNDLE)

    @Nls
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        if (Locale.getDefault().language == "zh") {
            try {
                val zh = ResourceBundle.getBundle(BUNDLE, Locale.SIMPLIFIED_CHINESE, LuaBundle::class.java.classLoader)
                val text = zh.getString(key)
                return if (params.isEmpty()) text else MessageFormat.format(text, *params)
            } catch (_: Exception) {
                // 中文缺这条时退回默认语言
            }
        }
        return instance.getMessage(key, *params)
    }
}

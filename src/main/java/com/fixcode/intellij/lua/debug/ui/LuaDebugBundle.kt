package com.fixcode.intellij.lua.debug.ui

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

@NonNls
private const val BUNDLE = "messages.LuaDebugBundle"

/**
 * 新调试 UI 的文案。单独一个 bundle，避免和语言支持的 LuaBundle 混在一起、并行开发时互相冲突。
 *
 * 和 LuaBundle 一样：系统语言是中文时直接用中文，不要求装 IDE 中文语言包。
 */
object LuaDebugBundle {
    private val instance = DynamicBundle(LuaDebugBundle::class.java, BUNDLE)

    @Nls
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        if (Locale.getDefault().language == "zh") {
            try {
                val zh = ResourceBundle.getBundle(BUNDLE, Locale.SIMPLIFIED_CHINESE, LuaDebugBundle::class.java.classLoader)
                val text = zh.getString(key)
                return if (params.isEmpty()) text else MessageFormat.format(text, *params)
            } catch (_: Exception) {
                // 中文缺这条时退回默认语言
            }
        }
        return instance.getMessage(key, *params)
    }
}

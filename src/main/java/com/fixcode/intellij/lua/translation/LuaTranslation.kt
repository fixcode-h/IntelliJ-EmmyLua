package com.fixcode.intellij.lua.translation

import com.intellij.DynamicBundle
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * IDE 里唯一的 [TranslationService]（第一次用时创建；每次请求都重新读设置，改了马上生效）。
 * 悬停文档和「翻译选中内容」共用。
 */
object LuaTranslation {
    internal val LOG = Logger.getInstance("EmmyLua.Translation")

    val service: TranslationService by lazy {
        val settings = { LuaTranslationSettings.instance }
        TranslationService(
            options = { optionsOf(settings()) },
            cache = TranslationCache(
                PathManager.getSystemDir().resolve("emmylua").resolve("translation").resolve("cache.jsonl"),
                background = AppExecutorUtil.getAppExecutorService(),
                log = { msg, e -> if (e == null) LOG.info(msg) else LOG.warn(msg, e) },
            ),
            providers = TranslationProviders.create { settings().googleUrl },
            http = IdeTranslationHttp,
            executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("EmmyLua Translation", TranslationService.MAX_CONCURRENT),
            log = object : TranslationLog {
                override fun debug(message: String) = LOG.debug(message)
                override fun warn(message: String) = LOG.warn(message)
            },
        )
    }

    /** 悬停文档要不要翻译（设置里的总开关）。 */
    fun hoverEnabled(): Boolean = LuaTranslationSettings.instance.enabled

    fun optionsOf(s: LuaTranslationSettings) = TranslationOptions(
        enabled = s.enabled,
        display = s.display,
        provider = s.provider,
        target = TranslationText.resolveTarget(s.target, englishUi = !chineseUi()),
    )

    /** IDE 界面是中文，或者系统是中文（英文界面装在中文系统上的也算）。 */
    private fun chineseUi(): Boolean =
        DynamicBundle.getLocale().language == "zh" || Locale.getDefault().language == "zh"

    /** 「翻译 · 谷歌翻译」 */
    fun header(): String = "翻译 · " + service.currentOptions.provider.label
}

/** 走 IDE 的 `HttpRequests`：用 IDE 的代理设置（设置 → HTTP 代理），谷歌连不上时配个代理就行。 */
internal object IdeTranslationHttp : TranslationHttp {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) EmmyLua"

    override fun get(url: String, headers: Map<String, String>, timeoutMillis: Int): HttpResult =
        exec(HttpRequests.request(url), headers, timeoutMillis, null)

    override fun post(url: String, body: String, contentType: String, headers: Map<String, String>, timeoutMillis: Int): HttpResult =
        exec(HttpRequests.post(url, contentType), headers, timeoutMillis, body)

    private fun exec(builder: RequestBuilder, headers: Map<String, String>, timeoutMillis: Int, body: String?): HttpResult =
        builder.connectTimeout(timeoutMillis).readTimeout(timeoutMillis).userAgent(USER_AGENT)
            .throwStatusCodeException(false)
            .tuner { c -> headers.forEach { (k, v) -> c.setRequestProperty(k, v) } }
            .connect { req ->
                if (body != null) req.write(body)
                val conn = req.connection as? HttpURLConnection
                val code = conn?.responseCode ?: 200
                val text = if (code in 200..299) req.readString()
                else conn?.errorStream?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }.orEmpty()
                HttpResult(code, text)
            }
}

package com.fixcode.intellij.lua.translation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** 发 HTTP 请求的口子：IDE 里用 `HttpRequests`（走 IDE 的代理设置），单测换成假的。 */
interface TranslationHttp {
    fun get(url: String, headers: Map<String, String>, timeoutMillis: Int): HttpResult
    fun post(url: String, body: String, contentType: String, headers: Map<String, String>, timeoutMillis: Int): HttpResult
}

data class HttpResult(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

class TranslationException(message: String) : Exception(message)

/** 一个免费翻译服务。实现只管发请求和解析回包。 */
interface TranslationProvider {
    val kind: TranslationProviderKind

    /** 一次请求最多发的字数（更长的段由 [TranslationText.chunk] 切开）。 */
    val maxChunkChars: Int

    /** 把 [text] 翻译成 [target]（如 zh-CN）。失败抛异常。 */
    fun translate(http: TranslationHttp, text: String, target: String, timeoutMillis: Int): String
}

private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

private fun parseJson(body: String): JsonElement =
    try {
        JsonParser.parseString(body)
    } catch (e: Exception) {
        throw TranslationException("unexpected response")
    }

/** 谷歌翻译，Chrome 网页翻译用的接口（client=gtx，不要密钥）。地址可以换成镜像。 */
class GoogleTranslateProvider(private val baseUrl: () -> String? = { null }) : TranslationProvider {
    override val kind = TranslationProviderKind.GOOGLE
    override val maxChunkChars = 1800

    override fun translate(http: TranslationHttp, text: String, target: String, timeoutMillis: Int): String {
        val r = http.get(requestUrl(baseUrl(), text, TranslationText.providerCode(kind, target)), emptyMap(), timeoutMillis)
        if (!r.ok) throw TranslationException("HTTP ${r.status}")
        return parse(r.body)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://translate.googleapis.com"

        fun normalizeBaseUrl(url: String?): String {
            var u = url.orEmpty().trim().trimEnd('/')
            if (u.isEmpty()) return DEFAULT_BASE_URL
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
            return u
        }

        fun requestUrl(baseUrl: String?, text: String, target: String): String =
            normalizeBaseUrl(baseUrl) + "/translate_a/single?client=gtx&sl=auto&tl=" + enc(target) + "&dt=t&q=" + enc(text)

        /** `[[["译文","原文",…],…],…,"en",…]` → 各句译文拼起来。 */
        fun parse(json: String): String {
            // 被限流时谷歌会跳到「异常流量」网页
            if (json.trimStart().startsWith("<")) throw TranslationException("谷歌返回的是网页不是译文（可能被限流或需要代理），可以换成微软翻译")
            val root = parseJson(json) as? JsonArray
            val sentences = root?.takeIf { it.size() > 0 }?.get(0) as? JsonArray ?: throw TranslationException("unexpected response")
            val sb = StringBuilder()
            for (s in sentences) {
                val a = s as? JsonArray ?: continue
                val first = a.takeIf { it.size() > 0 }?.get(0)
                if (first != null && first.isJsonPrimitive && first.asJsonPrimitive.isString) sb.append(first.asString)
            }
            if (sb.isEmpty()) throw TranslationException("empty translation")
            return sb.toString()
        }
    }
}

/**
 * 微软翻译，走 Edge 网页翻译用的匿名令牌（edge.microsoft.com/translate/auth，约 10 分钟有效，缓存 8 分钟）。
 * 谷歌连不上的地方通常能直接用。
 */
class MicrosoftTranslateProvider(private val now: () -> Long = System::currentTimeMillis) : TranslationProvider {
    override val kind = TranslationProviderKind.MICROSOFT
    override val maxChunkChars = 4000

    private val tokenLock = Any()
    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    override fun translate(http: TranslationHttp, text: String, target: String, timeoutMillis: Int): String {
        val auth = token(http, timeoutMillis)
        val url = API_URL + "?api-version=3.0&to=" + enc(TranslationText.providerCode(kind, target))
        val r = http.post(url, requestBody(text), "application/json", mapOf("Authorization" to "Bearer $auth"), timeoutMillis)
        if (r.status == 401) token = null
        if (!r.ok) throw TranslationException("HTTP ${r.status}")
        return parse(r.body)
    }

    private fun token(http: TranslationHttp, timeoutMillis: Int): String = synchronized(tokenLock) {
        token?.let { if (now() - tokenAt < TOKEN_LIFETIME_MS) return it }
        val r = http.get(AUTH_URL, emptyMap(), timeoutMillis)
        val body = r.body.trim()
        if (!r.ok || body.isEmpty()) throw TranslationException("auth HTTP ${r.status}")
        token = body
        tokenAt = now()
        body
    }

    companion object {
        const val AUTH_URL = "https://edge.microsoft.com/translate/auth"
        const val API_URL = "https://api-edge.cognitive.microsofttranslator.com/translate"
        const val TOKEN_LIFETIME_MS = 8 * 60 * 1000L

        fun requestBody(text: String): String = JsonArray().apply { add(JsonObject().apply { addProperty("Text", text) }) }.toString()

        /** `[{"translations":[{"text":"…","to":"zh-Hans"}]}]` → text。 */
        fun parse(json: String): String {
            val root = parseJson(json)
            val t = (root as? JsonArray)?.takeIf { it.size() > 0 }?.get(0)?.let { it as? JsonObject }
                ?.get("translations")?.let { it as? JsonArray }?.takeIf { it.size() > 0 }?.get(0)?.let { it as? JsonObject }
                ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            if (t.isNullOrEmpty()) {
                val err = (root as? JsonObject)?.get("error")?.let { it as? JsonObject }?.get("message")
                    ?.takeIf { it.isJsonPrimitive }?.asString
                throw TranslationException(err ?: "unexpected response")
            }
            return t
        }
    }
}

/** MyMemory（translated.net）：不要密钥；匿名一次最多 500 字节，每天约 5000 词。 */
class MyMemoryProvider : TranslationProvider {
    override val kind = TranslationProviderKind.MY_MEMORY
    override val maxChunkChars = 450

    override fun translate(http: TranslationHttp, text: String, target: String, timeoutMillis: Int): String {
        val r = http.get(requestUrl(text, TranslationText.providerCode(kind, target)), emptyMap(), timeoutMillis)
        if (!r.ok) throw TranslationException("HTTP ${r.status}")
        return parse(r.body)
    }

    companion object {
        const val API_URL = "https://api.mymemory.translated.net/get"

        fun requestUrl(text: String, target: String): String {
            val source = if (target.equals("en", ignoreCase = true)) "zh-CN" else "en"
            return API_URL + "?q=" + enc(text) + "&langpair=" + enc("$source|$target")
        }

        /** `{"responseData":{"translatedText":"…"},"responseStatus":200}` → text；别的状态报错并带上原因。 */
        fun parse(json: String): String {
            val o = parseJson(json) as? JsonObject ?: throw TranslationException("unexpected response")
            val st = o.get("responseStatus")
            val status = if (st != null && st.isJsonPrimitive) st.asString.trim().toIntOrNull() ?: 0 else 0
            val t = (o.get("responseData") as? JsonObject)?.get("translatedText")?.takeIf { it.isJsonPrimitive }?.asString
            if (status != 200 || t.isNullOrEmpty()) {
                val details = o.get("responseDetails")?.takeIf { it.isJsonPrimitive }?.asString
                throw TranslationException(details ?: "status $status")
            }
            return htmlDecode(t)
        }

        /** MyMemory 回的译文里有 `&#39;`、`&quot;` 这类实体。 */
        fun htmlDecode(s: String): String {
            if (!s.contains('&')) return s
            return ENTITY.replace(s) { m ->
                val e = m.groupValues[1]
                when {
                    e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> NAMED[e]
                } ?: m.value
            }
        }

        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
        private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    }
}

object TranslationProviders {
    fun create(googleBaseUrl: () -> String?): Map<TranslationProviderKind, TranslationProvider> = mapOf(
        TranslationProviderKind.GOOGLE to GoogleTranslateProvider(googleBaseUrl),
        TranslationProviderKind.MICROSOFT to MicrosoftTranslateProvider(),
        TranslationProviderKind.MY_MEMORY to MyMemoryProvider(),
    )
}

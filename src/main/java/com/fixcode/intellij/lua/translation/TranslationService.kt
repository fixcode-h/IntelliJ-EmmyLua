package com.fixcode.intellij.lua.translation

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** 生效的翻译设置（目标语言已经按界面语言解析好）。 */
data class TranslationOptions(
    val enabled: Boolean = true,
    val display: TranslationDisplay = TranslationDisplay.BILINGUAL,
    val provider: TranslationProviderKind = TranslationProviderKind.GOOGLE,
    val target: String = "zh-CN",
)

class TranslationResult private constructor(val text: String?, val error: String?, val fromCache: Boolean) {
    val ok: Boolean get() = text != null

    companion object {
        fun success(text: String, fromCache: Boolean) = TranslationResult(text, null, fromCache)
        fun failure(error: String?) = TranslationResult(null, error ?: "error", false)
    }
}

/**
 * 在后台翻译注释（照 UEToVS 的 `TranslationService`）：先查缓存（[TranslationCache]），再请求设置里选的服务。
 * 相同内容同时只发一次请求；最多 [MAX_CONCURRENT] 个请求并发（由传进来的 [executor] 限制），单次超时
 * [REQUEST_TIMEOUT_MS]；失败后 [COOLDOWN_MS] 内不再请求这个服务，直接返回上次的失败原因（悬停不用每次等超时）。
 *
 * 不碰 IntelliJ 的类，单测用假的 [TranslationHttp]。所有方法都不阻塞调用线程。
 */
class TranslationService(
    private val options: () -> TranslationOptions,
    val cache: TranslationCache,
    private val providers: Map<TranslationProviderKind, TranslationProvider>,
    private val http: TranslationHttp,
    private val executor: Executor,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: TranslationLog = TranslationLog.NONE,
) {
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<TranslationResult>>()
    private val cooldown = ConcurrentHashMap<TranslationProviderKind, Pair<Long, String>>()

    val currentOptions: TranslationOptions get() = options()

    /** 当前设置下 [text] 已缓存的译文；从不阻塞（界面线程上也能调）。 */
    fun cached(text: String): String? {
        val o = options()
        return cache.get(TranslationCache.key(o.provider, o.target, text), allowLoad = false)
    }

    fun translate(text: String?): CompletableFuture<TranslationResult> {
        if (text.isNullOrBlank()) return CompletableFuture.completedFuture(TranslationResult.failure("empty"))
        val o = options()
        val key = TranslationCache.key(o.provider, o.target, text)
        inFlight[key]?.let { return it }
        val future = CompletableFuture<TranslationResult>()
        val existing = inFlight.putIfAbsent(key, future)
        if (existing != null) return existing
        try {
            executor.execute {
                val r = try {
                    run(o, key, text)
                } catch (e: Throwable) {
                    TranslationResult.failure(e.message ?: e.javaClass.simpleName)
                }
                inFlight.remove(key)
                future.complete(r)
            }
        } catch (e: Exception) {
            inFlight.remove(key)
            future.complete(TranslationResult.failure(e.message ?: e.javaClass.simpleName))
        }
        return future
    }

    private fun run(o: TranslationOptions, key: String, text: String): TranslationResult {
        cache.get(key)?.let { return TranslationResult.success(it, true) }
        val provider = providers[o.provider] ?: return TranslationResult.failure("unknown provider ${o.provider}")
        cooldown[o.provider]?.let { (until, error) -> if (until > now()) return TranslationResult.failure(error) }

        val start = now()
        val chunks = TranslationText.chunk(text, provider.maxChunkChars)
        return try {
            val outputs = chunks.map { provider.translate(http, it, o.target, REQUEST_TIMEOUT_MS) }
            val result = TranslationText.joinChunks(outputs)
            cache.put(key, result)
            cooldown.remove(o.provider)
            log.debug("translate.ok provider=${o.provider.cacheName} target=${o.target} chars=${text.length} chunks=${chunks.size} ms=${now() - start}")
            TranslationResult.success(result, false)
        } catch (e: Exception) {
            val error = describe(e)
            cooldown[o.provider] = (now() + COOLDOWN_MS) to error
            log.warn("translate.failed provider=${o.provider.cacheName} target=${o.target} chars=${text.length} ms=${now() - start} error=$error")
            TranslationResult.failure(error)
        }
    }

    /** 忘掉失败，下一次请求重新试这个服务（改了设置、清空缓存时）。 */
    fun resetCooldown() = cooldown.clear()

    companion object {
        const val MAX_CONCURRENT = 4
        const val REQUEST_TIMEOUT_MS = 8000
        const val COOLDOWN_MS = 30_000L

        fun describe(e: Throwable): String = when (e) {
            is TranslationException -> e.message ?: "error"
            is java.net.SocketTimeoutException -> "timeout ${REQUEST_TIMEOUT_MS / 1000}s"
            is java.net.UnknownHostException -> "无法解析域名 ${e.message}"
            else -> e.javaClass.simpleName + ": " + (e.cause?.message ?: e.message)
        }
    }
}

/** 日志口子：IDE 里接 `Logger`，单测里不记。 */
interface TranslationLog {
    fun debug(message: String)
    fun warn(message: String)

    companion object {
        val NONE = object : TranslationLog {
            override fun debug(message: String) {}
            override fun warn(message: String) {}
        }
    }
}

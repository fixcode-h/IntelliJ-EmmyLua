package com.fixcode.intellij.lua.debug.api

import com.intellij.openapi.Disposable
import java.util.concurrent.CompletableFuture

/**
 * 调试会话的错误聚合（协议第 21 节，能力 errorReport），给调试窗口的「Lua 错误」页用。
 * 由引擎的 XDebugProcess 实现；UI 只认这个接口。监听器在 EDT 上收到通知。
 */
interface LuaErrorsModel {
    /** 已连上且 runtime 协商到了 errorReport 能力。 */
    fun errorsSupported(): Boolean

    /** 本地缓存（推送累积，计数可能略落后），不发请求。 */
    fun cachedErrors(): List<LuaErrorRow>

    /** 向 runtime 取精确计数并刷新缓存。 */
    fun refreshErrors(): CompletableFuture<List<LuaErrorRow>>

    fun clearErrors(): CompletableFuture<Unit>

    /** 有新错误、计数变了、清空、重连时调用（EDT）。 */
    fun addErrorsListener(listener: () -> Unit, parent: Disposable)
}

data class LuaErrorRow(
    val id: Long,
    val vmId: Long,
    val message: String,
    /** chunkName 原样；拿不到为空。 */
    val chunkName: String?,
    val line: Int?,
    /** 映射到的本地文件；没映射上为空。 */
    val localPath: String?,
    val count: Long,
    val firstMs: Long,
    val lastMs: Long,
    val uncaught: Boolean,
    val traceback: String?,
)

package com.fixcode.intellij.lua.debug.api

import java.util.concurrent.CompletableFuture

/**
 * 调试会话的采样性能分析（协议第 20 节，能力 profiler），给调试窗口的「Lua 采样」页用。
 * 由引擎的 XDebugProcess 实现；UI 只认这个接口，不依赖引擎模块。
 *
 * future 失败时异常的 message 是给用户看的中文说明（runtime 不支持、没连上、参数越界……）。
 */
interface LuaProfilerModel {
    /** 已连上且 runtime 协商到了 profiler 能力。连上之前、老 runtime 都是 false。 */
    fun profilerSupported(): Boolean

    /** 开始采样（所有 VM）；已在采样时清空重来。 */
    fun startProfiling(): CompletableFuture<Unit>

    /** 停止采样并取完整结果（函数表全部、折叠栈尽量全部）。 */
    fun stopProfiling(): CompletableFuture<LuaProfileSnapshot>

    /** 取当前结果，不停止。 */
    fun profileSnapshot(): CompletableFuture<LuaProfileSnapshot>
}

data class LuaProfileRow(
    /** 函数名；取不到时 `<匿名>`，主 chunk 是 `<主 chunk>`。 */
    val function: String,
    /** chunkName 原样，C 函数为 `=[C]`。 */
    val chunkName: String,
    /** 函数定义行；C 函数 -1、主 chunk 0。 */
    val line: Int,
    /** 映射到的本地文件；没映射上或 C 函数为空。 */
    val localPath: String?,
    val selfSamples: Long,
    val totalSamples: Long,
    val selfPercent: Double,
    val totalPercent: Double,
)

data class LuaProfileSnapshot(
    val running: Boolean,
    val samples: Long,
    val durationMs: Long,
    val rows: List<LuaProfileRow>,
    /** 折叠栈文本（每行「帧;帧;帧 样本数」），可直接存成 .folded 给 speedscope / flamegraph.pl。 */
    val folded: String,
    val totalStacks: Int,
    val stacksTruncated: Boolean,
)

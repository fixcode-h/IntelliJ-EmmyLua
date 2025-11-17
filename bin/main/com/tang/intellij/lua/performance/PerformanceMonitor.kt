/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.performance

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能监控工具
 * 
 * 用于跟踪优化效果和识别性能瓶颈
 * 
 * 使用示例：
 * ```kotlin
 * fun someFunction() {
 *     PerformanceMonitor.measure("functionName") {
 *         // 你的代码
 *     }
 * }
 * 
 * // 查看报告
 * println(PerformanceMonitor.getReport())
 * ```
 */
object PerformanceMonitor {
    private val LOG = Logger.getInstance(PerformanceMonitor::class.java)
    
    private val metrics = ConcurrentHashMap<String, MetricData>()
    private var enabled = true
    
    // 性能阈值（毫秒）
    private const val SLOW_THRESHOLD_MS = 100L
    private const val VERY_SLOW_THRESHOLD_MS = 500L
    
    /**
     * 指标数据
     */
    data class MetricData(
        val count: AtomicLong = AtomicLong(0),
        val totalTime: AtomicLong = AtomicLong(0),
        val maxTime: AtomicLong = AtomicLong(0),
        val minTime: AtomicLong = AtomicLong(Long.MAX_VALUE),
        val slowCount: AtomicLong = AtomicLong(0),
        val verySlowCount: AtomicLong = AtomicLong(0)
    ) {
        fun getAverage(): Long {
            val c = count.get()
            return if (c > 0) totalTime.get() / c else 0
        }
        
        fun getP95(): Long {
            // 简化版P95估算：max * 0.95
            return (maxTime.get() * 0.95).toLong()
        }
    }
    
    /**
     * 测量代码块执行时间
     * 
     * @param name 操作名称
     * @param block 要测量的代码块
     * @return 代码块的返回值
     */
    fun <T> measure(name: String, block: () -> T): T {
        if (!enabled) return block()
        
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val durationMs = (System.nanoTime() - start) / 1_000_000
            recordMetric(name, durationMs)
        }
    }
    
    /**
     * 记录指标
     */
    private fun recordMetric(name: String, durationMs: Long) {
        val data = metrics.getOrPut(name) { MetricData() }
        
        data.count.incrementAndGet()
        data.totalTime.addAndGet(durationMs)
        data.maxTime.updateAndGet { max -> maxOf(max, durationMs) }
        data.minTime.updateAndGet { min -> minOf(min, durationMs) }
        
        // 记录慢操作
        when {
            durationMs > VERY_SLOW_THRESHOLD_MS -> {
                data.verySlowCount.incrementAndGet()
                LOG.warn("Very slow operation: $name took ${durationMs}ms")
            }
            durationMs > SLOW_THRESHOLD_MS -> {
                data.slowCount.incrementAndGet()
                if (LOG.isDebugEnabled) {
                    LOG.debug("Slow operation: $name took ${durationMs}ms")
                }
            }
        }
    }
    
    /**
     * 获取性能报告
     */
    fun getReport(): String {
        if (metrics.isEmpty()) {
            return "Performance Monitor: No data collected"
        }
        
        return buildString {
            appendLine("=" .repeat(80))
            appendLine("Performance Monitor Report")
            appendLine("=" .repeat(80))
            appendLine()
            
            // 按总耗时排序
            val sortedMetrics = metrics.entries
                .sortedByDescending { it.value.totalTime.get() }
            
            sortedMetrics.forEach { (name, data) ->
                val count = data.count.get()
                val total = data.totalTime.get()
                val avg = data.getAverage()
                val max = data.maxTime.get()
                val min = if (data.minTime.get() == Long.MAX_VALUE) 0 else data.minTime.get()
                val p95 = data.getP95()
                val slowCount = data.slowCount.get()
                val verySlowCount = data.verySlowCount.get()
                
                appendLine("$name:")
                appendLine("  Count:      $count")
                appendLine("  Total:      ${total}ms")
                appendLine("  Average:    ${avg}ms")
                appendLine("  Min:        ${min}ms")
                appendLine("  Max:        ${max}ms")
                appendLine("  P95:        ${p95}ms")
                
                if (slowCount > 0 || verySlowCount > 0) {
                    appendLine("  Slow:       $slowCount (>${SLOW_THRESHOLD_MS}ms)")
                    appendLine("  Very Slow:  $verySlowCount (>${VERY_SLOW_THRESHOLD_MS}ms)")
                }
                
                appendLine()
            }
            
            // 总计
            val totalOperations = metrics.values.sumOf { it.count.get() }
            val totalTime = metrics.values.sumOf { it.totalTime.get() }
            val totalSlow = metrics.values.sumOf { it.slowCount.get() }
            val totalVerySlow = metrics.values.sumOf { it.verySlowCount.get() }
            
            appendLine("=" .repeat(80))
            appendLine("Summary:")
            appendLine("  Total Operations:  $totalOperations")
            appendLine("  Total Time:        ${totalTime}ms")
            appendLine("  Slow Operations:   $totalSlow (${"%.2f".format(totalSlow * 100.0 / totalOperations)}%)")
            appendLine("  Very Slow Ops:     $totalVerySlow (${"%.2f".format(totalVerySlow * 100.0 / totalOperations)}%)")
            appendLine("=" .repeat(80))
        }
    }
    
    /**
     * 获取简化的报告（只显示前N项）
     */
    fun getReportTop(n: Int = 10): String {
        if (metrics.isEmpty()) {
            return "Performance Monitor: No data collected"
        }
        
        return buildString {
            appendLine("Performance Monitor - Top $n Operations")
            appendLine("-" .repeat(60))
            
            metrics.entries
                .sortedByDescending { it.value.totalTime.get() }
                .take(n)
                .forEach { (name, data) ->
                    val avg = data.getAverage()
                    val count = data.count.get()
                    val total = data.totalTime.get()
                    appendLine("$name: avg=${avg}ms, count=$count, total=${total}ms")
                }
        }
    }
    
    /**
     * 获取特定操作的指标
     */
    fun getMetric(name: String): MetricData? {
        return metrics[name]
    }
    
    /**
     * 重置所有统计数据
     */
    fun reset() {
        metrics.clear()
        LOG.info("Performance metrics reset")
    }
    
    /**
     * 启用/禁用监控
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        LOG.info("Performance monitoring ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * 是否启用
     */
    fun isEnabled(): Boolean = enabled
    
    /**
     * 导出CSV格式的数据
     */
    fun exportCSV(): String {
        return buildString {
            appendLine("Name,Count,Total(ms),Average(ms),Min(ms),Max(ms),P95(ms),Slow,Very Slow")
            
            metrics.entries
                .sortedBy { it.key }
                .forEach { (name, data) ->
                    val count = data.count.get()
                    val total = data.totalTime.get()
                    val avg = data.getAverage()
                    val min = if (data.minTime.get() == Long.MAX_VALUE) 0 else data.minTime.get()
                    val max = data.maxTime.get()
                    val p95 = data.getP95()
                    val slow = data.slowCount.get()
                    val verySlow = data.verySlowCount.get()
                    
                    appendLine("$name,$count,$total,$avg,$min,$max,$p95,$slow,$verySlow")
                }
        }
    }
}


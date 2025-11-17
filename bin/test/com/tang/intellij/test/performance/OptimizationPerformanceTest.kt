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

package com.tang.intellij.test.performance

import com.tang.intellij.lua.performance.PerformanceMonitor
import com.tang.intellij.lua.psi.LuaPsiResolveUtil.ResolveResultCache
import com.tang.intellij.lua.ty.LuaTypeCache
import com.tang.intellij.lua.ty.LuaClassHierarchyCache
import com.tang.intellij.test.LuaTestBase
import org.junit.Test
import kotlin.test.assertTrue

/**
 * 性能优化验证测试
 * 
 * 测试已实施的优化是否达到预期效果：
 * 1. 弱引用PSI缓存
 * 2. 三层缓存架构
 * 3. 类继承层次缓存
 */
class OptimizationPerformanceTest : LuaTestBase() {
    
    override fun setUp() {
        super.setUp()
        // 启用性能监控
        PerformanceMonitor.setEnabled(true)
        PerformanceMonitor.reset()
    }
    
    override fun tearDown() {
        // 输出性能报告
        println(PerformanceMonitor.getReport())
        super.tearDown()
    }
    
    /**
     * 测试1: 小文件补全性能
     * 目标: 平均 <50ms
     */
    @Test
    fun testSmallFileCompletion() {
        val code = """
            local x = 10
            local y = 20
            local z = x + y
            
            function test()
                local a = 1
                <caret>
            end
        """.trimIndent()
        
        myFixture.configureByText("small.lua", code)
        
        // 预热
        myFixture.completeBasic()
        
        // 测试多次取平均
        val durations = mutableListOf<Long>()
        repeat(10) {
            val start = System.currentTimeMillis()
            myFixture.completeBasic()
            val duration = System.currentTimeMillis() - start
            durations.add(duration)
        }
        
        val avgDuration = durations.average()
        println("小文件补全平均耗时: ${avgDuration}ms")
        
        assertTrue(avgDuration < 50, "小文件补全应 <50ms，实际: ${avgDuration}ms")
    }
    
    /**
     * 测试2: 大文件补全性能  
     * 目标: 平均 <150ms
     */
    @Test
    fun testLargeFileCompletion() {
        val largeCode = createLargeFile(lines = 2000)
        myFixture.configureByText("large.lua", largeCode)
        
        // 预热
        myFixture.completeBasic()
        
        val durations = mutableListOf<Long>()
        repeat(5) {
            val start = System.currentTimeMillis()
            myFixture.completeBasic()
            val duration = System.currentTimeMillis() - start
            durations.add(duration)
        }
        
        val avgDuration = durations.average()
        println("大文件补全平均耗时: ${avgDuration}ms")
        
        assertTrue(avgDuration < 150, "大文件补全应 <150ms，实际: ${avgDuration}ms")
    }
    
    /**
     * 测试3: 深层继承类成员补全
     * 目标: 平均 <100ms
     */
    @Test
    fun testDeepInheritanceCompletion() {
        val code = createDeepInheritanceCode(depth = 10)
        myFixture.configureByText("inheritance.lua", code)
        
        // 预热
        myFixture.completeBasic()
        
        val durations = mutableListOf<Long>()
        repeat(10) {
            val start = System.currentTimeMillis()
            myFixture.completeBasic()
            val duration = System.currentTimeMillis() - start
            durations.add(duration)
        }
        
        val avgDuration = durations.average()
        println("深层继承补全平均耗时: ${avgDuration}ms")
        
        assertTrue(avgDuration < 100, "深层继承补全应 <100ms，实际: ${avgDuration}ms")
    }
    
    /**
     * 测试4: 三层缓存命中率
     * 目标: 命中率 >80%
     */
    @Test
    fun testTypeCacheHitRate() {
        val code = """
            local x = 10
            local y = 20
            local z = x + y
            
            function test()
                return x + y + z
            end
            
            test()<caret>
        """.trimIndent()
        
        myFixture.configureByText("cache_test.lua", code)
        
        // 清空缓存
        val typeCache = LuaTypeCache.getInstance(myFixture.project)
        typeCache.clear()
        
        // 执行多次补全，后续应从缓存获取
        repeat(20) {
            myFixture.completeBasic()
        }
        
        val stats = typeCache.getStats()
        println("类型缓存统计:")
        println(stats)
        
        // 验证命中率
        assertTrue(stats.hitRate > 0.8, "缓存命中率应 >80%，实际: ${"%.2f".format(stats.hitRate * 100)}%")
    }
    
    /**
     * 测试5: 类继承层次缓存效果
     * 目标: 后续查询 <10ms
     */
    @Test
    fun testClassHierarchyCachePerformance() {
        val code = createDeepInheritanceCode(depth = 10)
        myFixture.configureByText("hierarchy_test.lua", code)
        
        val hierarchyCache = LuaClassHierarchyCache.getInstance(myFixture.project)
        hierarchyCache.clear()
        
        // 第一次查询（构建缓存）
        val firstQueryTime = measureTime {
            myFixture.completeBasic()
        }
        println("首次查询(构建缓存): ${firstQueryTime}ms")
        
        // 后续查询（从缓存读取）
        val cachedQueryTimes = mutableListOf<Long>()
        repeat(10) {
            val time = measureTime {
                myFixture.completeBasic()
            }
            cachedQueryTimes.add(time)
        }
        
        val avgCachedTime = cachedQueryTimes.average()
        println("缓存查询平均耗时: ${avgCachedTime}ms")
        
        // 缓存查询应明显快于首次查询
        assertTrue(avgCachedTime < firstQueryTime * 0.3, 
            "缓存查询应比首次快70%以上，首次: ${firstQueryTime}ms，缓存: ${avgCachedTime}ms")
        
        // 验证缓存统计
        val stats = hierarchyCache.getStats()
        println("继承层次缓存统计:")
        println(stats)
    }
    
    /**
     * 测试6: PSI缓存内存管理
     * 验证弱引用是否正常工作
     */
    @Test
    fun testPsiCacheMemoryManagement() {
        val code = """
            local x = 1
            <caret>
        """.trimIndent()
        
        myFixture.configureByText("memory_test.lua", code)
        
        // 清空缓存
        ResolveResultCache.clear()
        
        // 执行大量操作
        repeat(100) {
            myFixture.completeBasic()
        }
        
        val beforeGCStats = ResolveResultCache.getStats()
        println("GC前缓存统计: $beforeGCStats")
        
        // 触发GC
        System.gc()
        Thread.sleep(100)
        
        val afterGCStats = ResolveResultCache.getStats()
        println("GC后缓存统计: $afterGCStats")
        
        // 验证缓存仍然有效
        assertTrue(afterGCStats.validEntries > 0, "GC后应仍有有效缓存")
        
        // 验证命中率
        assertTrue(afterGCStats.hitRate > 0, "应有缓存命中")
    }
    
    /**
     * 测试7: 整体性能对比
     * 显示所有指标的综合报告
     */
    @Test
    fun testOverallPerformance() {
        println("\n" + "=".repeat(80))
        println("整体性能测试报告")
        println("=".repeat(80))
        
        // 小文件测试
        testSmallFileCompletion()
        
        // 大文件测试
        testLargeFileCompletion()
        
        // 深层继承测试
        testDeepInheritanceCompletion()
        
        // 缓存测试
        testTypeCacheHitRate()
        testClassHierarchyCachePerformance()
        
        // 输出所有缓存统计
        println("\n" + "=".repeat(80))
        println("缓存统计总览")
        println("=".repeat(80))
        
        val typeCache = LuaTypeCache.getInstance(myFixture.project)
        println("\n类型缓存:")
        println(typeCache.getStats())
        
        val hierarchyCache = LuaClassHierarchyCache.getInstance(myFixture.project)
        println("\n继承层次缓存:")
        println(hierarchyCache.getStats())
        
        val resolveCache = ResolveResultCache.getStats()
        println("\nPSI解析缓存:")
        println(resolveCache)
        
        println("\n" + "=".repeat(80))
    }
    
    // === 辅助方法 ===
    
    private fun createLargeFile(lines: Int): String {
        return buildString {
            repeat(lines) { i ->
                appendLine("local var$i = $i")
                if (i % 10 == 0) {
                    appendLine("function func$i()")
                    appendLine("  return var$i")
                    appendLine("end")
                }
            }
            appendLine("<caret>")
        }
    }
    
    private fun createDeepInheritanceCode(depth: Int): String {
        return buildString {
            // 创建深层继承结构
            appendLine("---@class Base")
            appendLine("local Base = {}")
            appendLine("function Base:baseMethod() end")
            appendLine()
            
            var currentClass = "Base"
            for (i in 1..depth) {
                val newClass = "Level$i"
                appendLine("---@class $newClass : $currentClass")
                appendLine("local $newClass = {}")
                appendLine("function $newClass:method$i() end")
                appendLine()
                currentClass = newClass
            }
            
            // 在最深层的类上触发补全
            appendLine("local obj = $currentClass")
            appendLine("obj:<caret>")
        }
    }
    
    private fun measureTime(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }
}


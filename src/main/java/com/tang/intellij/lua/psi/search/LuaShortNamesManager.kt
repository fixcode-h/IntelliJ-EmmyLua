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

package com.tang.intellij.lua.psi.search

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.Processor
import com.tang.intellij.lua.comment.psi.LuaDocTagField
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.ITyClass
import com.tang.intellij.lua.ty.TyParameter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.lang.ref.WeakReference

/**
 * 缓存条目，包含值和时间戳
 */
data class CacheEntry<T>(val value: T, val timestamp: Long = System.currentTimeMillis())

/**
 * 内存管理器，监控和管理缓存内存使用
 */
object CacheMemoryManager {
    private val runtime = Runtime.getRuntime()
    private const val MEMORY_THRESHOLD = 0.8 // 80%内存使用率阈值
    private const val CACHE_SIZE_LIMIT = 10000 // 单个缓存最大条目数
    
    fun isMemoryPressure(): Boolean {
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        return usedMemory.toDouble() / runtime.maxMemory() > MEMORY_THRESHOLD
    }
    
    fun shouldLimitCacheSize(currentSize: Int): Boolean {
        return currentSize > CACHE_SIZE_LIMIT || isMemoryPressure()
    }
}

/**
 * 带有TTL和内存管理的缓存管理器
 */
class TTLCache<K, V>(private val ttlMillis: Long = TimeUnit.MINUTES.toMillis(5)) {
    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    private var lastCleanupTime = System.currentTimeMillis()
    private val cleanupInterval = TimeUnit.MINUTES.toMillis(2)
    
    fun get(key: K): V? {
        // 定期清理过期条目
        if (System.currentTimeMillis() - lastCleanupTime > cleanupInterval) {
            cleanExpired()
            lastCleanupTime = System.currentTimeMillis()
        }
        
        val entry = cache[key]
        return if (entry != null && !isExpired(entry)) {
            entry.value
        } else {
            cache.remove(key)
            null
        }
    }
    
    fun put(key: K, value: V) {
        // 检查内存压力和缓存大小
        if (CacheMemoryManager.shouldLimitCacheSize(cache.size)) {
            // 清理最旧的条目
            cleanOldestEntries(cache.size / 4) // 清理25%的条目
        }
        cache[key] = CacheEntry(value)
    }
    
    fun clear() {
        cache.clear()
    }
    
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { (_, entry) -> now - entry.timestamp > ttlMillis }
    }
    
    private fun cleanOldestEntries(count: Int) {
        val sortedEntries = cache.entries.sortedBy { it.value.timestamp }
        var removed = 0
        for ((key, _) in sortedEntries) {
            if (removed >= count) break
            cache.remove(key)
            removed++
        }
    }
    
    private fun isExpired(entry: CacheEntry<V>): Boolean {
        return System.currentTimeMillis() - entry.timestamp > ttlMillis
    }
    
    fun size(): Int = cache.size
    
    fun getMemoryInfo(): String {
        return "Cache size: ${cache.size}, Memory pressure: ${CacheMemoryManager.isMemoryPressure()}"
    }
}

abstract class LuaShortNamesManager {
    companion object {
        val EP_NAME = ExtensionPointName.create<LuaShortNamesManager>("com.tang.intellij.lua.luaShortNamesManager")

        private val KEY = Key.create<LuaShortNamesManager>("com.tang.intellij.lua.luaShortNamesManager")

        fun getInstance(project: Project): LuaShortNamesManager {
            var instance = project.getUserData(KEY)
            if (instance == null) {
                instance = CompositeLuaShortNamesManager()
                project.putUserData(KEY, instance)
            }
            return instance
        }
    }
    
    // 缓存实例
    protected val classCache = TTLCache<String, LuaClass?>()
    protected val memberCache = TTLCache<String, LuaClassMember?>()
    protected val methodCache = TTLCache<String, LuaClassMethod?>()
    protected val classMembersCache = TTLCache<String, Collection<LuaClassMember>>()
    
    /**
     * 清理所有缓存
     */
    open fun clearCaches() {
        classCache.clear()
        memberCache.clear()
        methodCache.clear()
        classMembersCache.clear()
    }
    
    /**
     * 清理过期缓存
     */
    open fun cleanExpiredCaches() {
        classCache.cleanExpired()
        memberCache.cleanExpired()
        methodCache.cleanExpired()
        classMembersCache.cleanExpired()
    }
    
    /**
     * 获取缓存内存使用信息
     */
    open fun getCacheMemoryInfo(): String {
        return "LuaShortNamesManager Cache Info:\n" +
                "Class cache: ${classCache.getMemoryInfo()}\n" +
                "Member cache: ${memberCache.getMemoryInfo()}\n" +
                "Method cache: ${methodCache.getMemoryInfo()}\n" +
                "ClassMembers cache: ${classMembersCache.getMemoryInfo()}"
    }
    
    /**
     * 强制进行内存清理
     */
    open fun forceMemoryCleanup() {
        if (CacheMemoryManager.isMemoryPressure()) {
            // 在内存压力下，清理所有缓存
            clearCaches()
            System.gc() // 建议垃圾回收
        } else {
            // 正常情况下，只清理过期缓存
            cleanExpiredCaches()
        }
    }

    open fun findClass(name: String, context: SearchContext): LuaClass? {
        val cacheKey = "${name}_${context.hashCode()}"
        return classCache.get(cacheKey) ?: run {
            val result = findClassImpl(name, context)
            classCache.put(cacheKey, result)
            result
        }
    }
    
    /**
     * 实际的类查找实现，子类应该重写此方法而不是findClass
     */
    protected open fun findClassImpl(name: String, context: SearchContext): LuaClass? = null

    open fun findMember(type: ITyClass, fieldName: String, context: SearchContext): LuaClassMember? {
        val cacheKey = "${type.className}_${fieldName}_${context.hashCode()}"
        return memberCache.get(cacheKey) ?: run {
            var perfect: LuaClassMember? = null
            var tagField: LuaDocTagField? = null
            var tableField: LuaTableField? = null
            processMembers(type, fieldName, context) {
                when (it) {
                    is LuaDocTagField -> {
                        tagField = it
                        false
                    }

                    is LuaTableField -> {
                        tableField = it
                        true
                    }

                    else -> {
                        if (perfect == null)
                            perfect = it
                        true
                    }
                }
            }
            val result = if (tagField != null) tagField else if (tableField != null) tableField else perfect
            memberCache.put(cacheKey, result)
            result
        }
    }

    open fun findMethod(
        className: String,
        methodName: String,
        context: SearchContext,
        visitSuper: Boolean = true
    ): LuaClassMethod? {
        val cacheKey = "${className}_${methodName}_${visitSuper}_${context.hashCode()}"
        return methodCache.get(cacheKey) ?: run {
            var target: LuaClassMethod? = null
            processMembers(className, methodName, context, Processor {
                if (it is LuaClassMethod) {
                    target = it
                    return@Processor false
                }
                true
            }, visitSuper)
            methodCache.put(cacheKey, target)
            target
        }
    }

    

    open fun processClassNames(project: Project, processor: Processor<String>): Boolean {
        return true
    }

    open fun processClassesWithName(name: String, context: SearchContext, processor: Processor<LuaClass>): Boolean {
        return true
    }

    open fun getClassMembers(clazzName: String, context: SearchContext): Collection<LuaClassMember> {
        val cacheKey = "${clazzName}_members_${context.hashCode()}"
        return classMembersCache.get(cacheKey) ?: run {
            val result = getClassMembersImpl(clazzName, context)
            classMembersCache.put(cacheKey, result)
            result
        }
    }
    
    /**
     * 实际的类成员获取实现，子类应该重写此方法而不是getClassMembers
     */
    protected open fun getClassMembersImpl(clazzName: String, context: SearchContext): Collection<LuaClassMember> {
        return emptyList()
    }



    open fun processMembers(
        type: ITyClass,
        memberName: String,
        context: SearchContext,
        processor: Processor<LuaClassMember>
    ): Boolean {
        return if (type is TyParameter)
            type.superClassName?.let { processMembers(it, memberName, context, processor) } ?: true
        else processMembers(type.className, memberName, context, processor)
    }

    open fun processMembers(
        className: String,
        fieldName: String,
        context: SearchContext,
        processor: Processor<LuaClassMember>,
        visitSuper: Boolean = true
    ): Boolean {
        return true
    }



    open fun processMembers(
        type: ITyClass,
        context: SearchContext,
        processor: Processor<LuaClassMember>
    ): Boolean {
        return true
    }

    open fun findAlias(name: String, context: SearchContext): LuaTypeAlias? = null

    open fun processAllAlias(project: Project, processor: Processor<String>): Boolean {
        return true
    }

    open fun findTypeDef(name: String, context: SearchContext): LuaTypeDef? {
        return findClass(name, context) ?: findAlias(name, context)
    }
}
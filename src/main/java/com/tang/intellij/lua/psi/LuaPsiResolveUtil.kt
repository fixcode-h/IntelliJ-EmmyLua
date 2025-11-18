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

package com.tang.intellij.lua.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.psi.search.LuaShortNamesManager
import com.tang.intellij.lua.search.SearchContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.PriorityQueue

/**
 * 解析结果缓存条目（使用弱引用避免内存泄漏）
 */
data class ResolveResultCacheEntry(
    val resultRef: WeakReference<PsiElement?>,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 获取缓存的PSI元素，自动验证有效性
     */
    fun get(): PsiElement? {
        val element = resultRef.get()
        return if (element != null && element.isValid) element else null
    }
}

/**
 * 解析结果缓存管理器（优化版）
 * 
 * 优化点：
 * 1. 使用弱引用避免内存泄漏
 * 2. 使用优先队列优化过期清理（O(log n)）
 * 3. 自动清理无效引用
 */
object ResolveResultCache {
    private val cache = ConcurrentHashMap<String, ResolveResultCacheEntry>()
    private val TTL_MILLIS = TimeUnit.MINUTES.toMillis(3) // 3分钟TTL
    private const val MAX_CACHE_SIZE = 5000
    
    // 使用优先队列实现高效的过期清理
    private val expirationQueue = PriorityQueue<ExpirationEntry>(
        compareBy { it.expirationTime }
    )
    
    private data class ExpirationEntry(
        val key: String,
        val expirationTime: Long
    )
    
    // 统计信息
    private var hits: Long = 0
    private var misses: Long = 0
    
    /**
     * 获取缓存的元素
     */
    fun get(key: String): PsiElement? {
        val entry = cache[key]
        
        if (entry == null) {
            misses++
            return null
        }
        
        // 检查是否过期
        if (isExpired(entry)) {
            cache.remove(key)
            misses++
            return null
        }
        
        // 尝试获取弱引用的值
        val element = entry.get()
        if (element == null) {
            // 弱引用已被GC回收
            cache.remove(key)
            misses++
            return null
        }
        
        hits++
        return element
    }
    
    /**
     * 存储元素到缓存
     */
    fun put(key: String, result: PsiElement?) {
        // 定期清理过期和无效条目
        if (cache.size >= MAX_CACHE_SIZE) {
            cleanExpiredFromQueue()
            // 如果清理后仍然过大，清理最旧的条目
            if (cache.size >= MAX_CACHE_SIZE) {
                cleanOldest(MAX_CACHE_SIZE / 4)
            }
        }
        
        if (result != null) {
            val entry = ResolveResultCacheEntry(WeakReference(result))
            cache[key] = entry
            
            // 添加到过期队列
            synchronized(expirationQueue) {
                expirationQueue.offer(ExpirationEntry(
                    key,
                    entry.timestamp + TTL_MILLIS
                ))
            }
        } else {
            cache.remove(key)
        }
    }
    
    /**
     * 清除所有缓存
     */
    fun clear() {
        cache.clear()
        synchronized(expirationQueue) {
            expirationQueue.clear()
        }
        hits = 0
        misses = 0
    }
    
    /**
     * 检查条目是否过期
     */
    private fun isExpired(entry: ResolveResultCacheEntry): Boolean {
        return System.currentTimeMillis() - entry.timestamp > TTL_MILLIS
    }
    
    /**
     * 使用优先队列高效清理过期条目
     * 时间复杂度: O(k log n) 其中 k 是过期条目数
     */
    private fun cleanExpiredFromQueue() {
        val now = System.currentTimeMillis()
        synchronized(expirationQueue) {
            while (expirationQueue.isNotEmpty()) {
                val oldest = expirationQueue.peek()
                if (oldest.expirationTime > now) break
                
                expirationQueue.poll()
                cache.remove(oldest.key)
            }
        }
    }
    
    /**
     * 清理最旧的N个条目
     */
    private fun cleanOldest(count: Int) {
        val entries = cache.entries
            .sortedBy { it.value.timestamp }
            .take(count)
        
        entries.forEach { (key, _) ->
            cache.remove(key)
        }
    }
    
    /**
     * 创建缓存键
     */
    fun createCacheKey(refName: String, fileUrl: String, offset: Int): String {
        return "$refName:$fileUrl:$offset"
    }
    
    /**
     * 获取缓存统计信息
     * 注意：不检查每个元素的有效性，避免需要 read access
     */
    fun getStats(): CacheStats {
        // 统计已被GC清理的弱引用数量（不访问PSI）
        val nullRefCount = cache.values.count { it.resultRef.get() == null }
        val estimatedValid = cache.size - nullRefCount
        
        return CacheStats(
            size = cache.size,
            validEntries = estimatedValid,
            invalidEntries = nullRefCount,
            hits = hits,
            misses = misses,
            hitRate = if (hits + misses > 0) hits.toDouble() / (hits + misses) else 0.0
        )
    }
    
    data class CacheStats(
        val size: Int,
        val validEntries: Int,
        val invalidEntries: Int,
        val hits: Long,
        val misses: Long,
        val hitRate: Double
    ) {
        override fun toString(): String {
            return "CacheStats(size=$size, valid=$validEntries, invalid=$invalidEntries, " +
                   "hits=$hits, misses=$misses, hitRate=${"%.2f".format(hitRate * 100)}%)"
        }
    }
}

fun resolveLocal(ref: LuaNameExpr, context: SearchContext? = null) = resolveLocal(ref.name, ref, context)

fun resolveLocal(refName:String, ref: PsiElement, context: SearchContext? = null): PsiElement? {
    val element = resolveInFile(refName, ref, context)
    return if (element is LuaNameExpr) null else element
}

fun resolveInFile(refName:String, pin: PsiElement, context: SearchContext?): PsiElement? {
    // 创建缓存键
    val containingFile = pin.containingFile
    val fileUrl = containingFile.virtualFile?.url ?: containingFile.name
    val cacheKey = ResolveResultCache.createCacheKey(refName, fileUrl, pin.textOffset)
    
    // 尝试从缓存获取结果（get方法会自动清理无效缓存）
    val cachedResult = ResolveResultCache.get(cacheKey)
    if (cachedResult != null && cachedResult.isValid) {
        return cachedResult
    }
    
    var ret: PsiElement? = null
    
    // 优化：先检查常见的self引用情况
    if (refName == Constants.WORD_SELF) {
        val methodDef = PsiTreeUtil.getStubOrPsiParentOfType(pin, LuaClassMethodDef::class.java)
        if (methodDef != null && !methodDef.isStatic) {
            val methodName = methodDef.classMethodName
            val expr = methodName.expr
            ret = if (expr is LuaNameExpr && context != null && expr.name != Constants.WORD_SELF)
                resolve(expr, context)
            else
                expr
        }
    }
    
    // 如果不是self或者没找到，进行常规查找
    if (ret == null) {
        val declarationTree = LuaDeclarationTree.get(containingFile)
        declarationTree.walkUp(pin) { decl ->
            if (decl.name == refName) {
                ret = decl.firstDeclaration.psi
                false // 找到后停止遍历
            } else {
                true // 继续遍历
            }
        }
    }
    
    // 只有当结果有效时才缓存
    if (ret != null && ret.isValid) {
        ResolveResultCache.put(cacheKey, ret)
    }
    return ret
}

fun isUpValue(ref: LuaNameExpr, context: SearchContext): Boolean {
    val funcBody = PsiTreeUtil.getParentOfType(ref, LuaFuncBody::class.java) ?: return false

    val refName = ref.name
    if (refName == Constants.WORD_SELF) {
        val classMethodFuncDef = PsiTreeUtil.getParentOfType(ref, LuaClassMethodDef::class.java)
        if (classMethodFuncDef != null && !classMethodFuncDef.isStatic) {
            val methodFuncBody = classMethodFuncDef.funcBody
            if (methodFuncBody != null)
                return methodFuncBody.textOffset < funcBody.textOffset
        }
    }

    val resolve = resolveLocal(ref, context)
    if (resolve != null) {
        if (!funcBody.textRange.contains(resolve.textRange))
            return true
    }

    return false
}

/**
 * 查找这个引用
 * @param nameExpr 要查找的ref
 * *
 * @param context context
 * *
 * @return PsiElement
 */
fun resolve(nameExpr: LuaNameExpr, context: SearchContext): PsiElement? {
    // 创建全局解析缓存键
    val fileUrl = nameExpr.containingFile.virtualFile?.url ?: nameExpr.containingFile.name
    val globalCacheKey = "global:${nameExpr.name}:${nameExpr.moduleName ?: Constants.WORD_G}:$fileUrl:${nameExpr.textOffset}"
    
    // 尝试从缓存获取全局解析结果
    val cachedGlobalResult = ResolveResultCache.get(globalCacheKey)
    if (cachedGlobalResult != null && cachedGlobalResult.isValid) {
        return cachedGlobalResult
    }
    
    //search local
    var resolveResult = resolveInFile(nameExpr.name, nameExpr, context)

    //global
    if (resolveResult == null || resolveResult is LuaNameExpr) {
        val target = (resolveResult as? LuaNameExpr) ?: nameExpr
        val refName = target.name
        val moduleName = target.moduleName ?: Constants.WORD_G
        LuaShortNamesManager.getInstance(context.project).processMembers(moduleName, refName, context, {
            resolveResult = it
            false
        })
        
        // 只有当结果有效且不是当前元素时才缓存全局解析结果
        val finalResult = resolveResult
        if (finalResult != null && finalResult.isValid && finalResult !== nameExpr) {
            ResolveResultCache.put(globalCacheKey, finalResult)
        }
    }

    return resolveResult
}

fun multiResolve(ref: LuaNameExpr, context: SearchContext): Array<PsiElement> {
    val list = mutableListOf<PsiElement>()
    //search local
    val resolveResult = resolveInFile(ref.name, ref, context)
    if (resolveResult != null) {
        list.add(resolveResult)
    } else {
        val refName = ref.name
        val module = ref.moduleName ?: Constants.WORD_G
        LuaShortNamesManager.getInstance(context.project).processMembers(module, refName, context, {
            list.add(it)
            true
        })
    }
    return list.toTypedArray()
}

fun multiResolve(indexExpr: LuaIndexExpr, context: SearchContext): List<PsiElement> {
    val list = mutableListOf<PsiElement>()
    val name = indexExpr.name ?: return list
    val type = indexExpr.guessParentType(context)
    type.eachTopClass(Processor { ty ->
        val m = ty.findMember(name, context)
        if (m != null)
            list.add(m)
        true
    })
    if (list.isEmpty()) {
        val tree = LuaDeclarationTree.get(indexExpr.containingFile)
        val declaration = tree.find(indexExpr)
        if (declaration != null) {
            list.add(declaration.psi)
        }
    }
    return list
}

fun resolve(indexExpr: LuaIndexExpr, context: SearchContext): PsiElement? {
    val name = indexExpr.name ?: return null
    return resolve(indexExpr, name, context)
}

fun resolve(indexExpr: LuaIndexExpr, idString: String, context: SearchContext): PsiElement? {
    val type = indexExpr.guessParentType(context)
    var ret: PsiElement? = null
    type.eachTopClass(Processor { ty ->
        ret = ty.findMember(idString, context)
        if (ret != null)
            return@Processor false
        true
    })
    if (ret == null) {
        val tree = LuaDeclarationTree.get(indexExpr.containingFile)
        val declaration = tree.find(indexExpr)
        if (declaration != null) {
            return declaration.psi
        }
    }
    return ret
}

/**
 * 找到 require 的文件路径
 * @param pathString 参数字符串 require "aa.bb.cc"
 * *
 * @param project MyProject
 * *
 * @return PsiFile
 */
fun resolveRequireFile(pathString: String?, project: Project): LuaPsiFile? {
    if (pathString == null)
        return null
    val fileName = pathString.replace('.', '/')
    var f = LuaFileUtil.findFile(project, fileName)

    // issue #415, support init.lua
    if (f == null || f.isDirectory) {
        f = LuaFileUtil.findFile(project, "$fileName/init")
    }

    if (f != null) {
        val psiFile = PsiManager.getInstance(project).findFile(f)
        if (psiFile is LuaPsiFile)
            return psiFile
    }
    return null
}
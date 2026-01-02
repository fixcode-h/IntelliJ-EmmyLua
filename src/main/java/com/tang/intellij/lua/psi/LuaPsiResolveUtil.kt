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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 解析结果缓存条目
 */
data class ResolveResultCacheEntry(val result: PsiElement?, val timestamp: Long = System.currentTimeMillis())

/**
 * 解析结果缓存管理器
 */
object ResolveResultCache {
    private val cache = ConcurrentHashMap<String, ResolveResultCacheEntry>()
    private val TTL_MILLIS = TimeUnit.MINUTES.toMillis(3) // 3分钟TTL
    private const val MAX_CACHE_SIZE = 5000
    private var lastCleanupTime = System.currentTimeMillis()
    private val cleanupInterval = TimeUnit.MINUTES.toMillis(1)
    
    fun get(key: String): PsiElement? {
        // 定期清理过期条目
        if (System.currentTimeMillis() - lastCleanupTime > cleanupInterval) {
            cleanExpired()
            lastCleanupTime = System.currentTimeMillis()
        }
        
        val entry = cache[key]
        return if (entry != null && !isExpired(entry)) {
            entry.result
        } else {
            cache.remove(key)
            null
        }
    }
    
    fun put(key: String, result: PsiElement?) {
        // 检查缓存大小限制
        if (cache.size >= MAX_CACHE_SIZE) {
            cleanOldestEntries(cache.size / 4) // 清理25%的条目
        }
        cache[key] = ResolveResultCacheEntry(result)
    }
    
    fun clear() {
        cache.clear()
    }
    
    private fun isExpired(entry: ResolveResultCacheEntry): Boolean {
        return System.currentTimeMillis() - entry.timestamp > TTL_MILLIS
    }
    
    private fun cleanExpired() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { (_, entry) -> now - entry.timestamp > TTL_MILLIS }
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
    
    fun createCacheKey(refName: String, fileUrl: String, offset: Int): String {
        return "$refName:$fileUrl:$offset"
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
    
    // 尝试从缓存获取结果
    val cachedResult = ResolveResultCache.get(cacheKey)
    if (cachedResult != null || ResolveResultCache.get(cacheKey) == null) {
        // 如果缓存中有结果（包括null结果），直接返回
        if (cachedResult != null && cachedResult.isValid) {
            return cachedResult
        }
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
    
    // 缓存结果
    ResolveResultCache.put(cacheKey, ret)
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
        
        // 缓存全局解析结果
        ResolveResultCache.put(globalCacheKey, resolveResult)
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
    
    // 当前文件优先查找逻辑
    val currentFileMember = resolveInCurrentFileFirst(indexExpr, name, context)
    if (currentFileMember != null) {
        list.add(currentFileMember)
        return list
    }
    
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
    // 当前文件优先查找逻辑
    val currentFileMember = resolveInCurrentFileFirst(indexExpr, idString, context)
    if (currentFileMember != null) {
        return currentFileMember
    }
    
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
 * 当前文件优先查找逻辑
 * - self:Method() -> 始终优先在当前文件查找
 * - LocalVar:Method() -> 仅当文件名是 LocalVar.lua 时优先在当前文件查找
 */
private fun resolveInCurrentFileFirst(indexExpr: LuaIndexExpr, methodName: String, context: SearchContext): PsiElement? {
    val prefixExpr = PsiTreeUtil.getStubChildOfType(indexExpr, LuaExpr::class.java) ?: return null
    val containingFile = indexExpr.containingFile
    
    // 获取前缀表达式的名称
    val prefixName = when (prefixExpr) {
        is LuaNameExpr -> prefixExpr.name
        else -> null
    } ?: return null
    
    // 判断是否应该优先在当前文件查找
    val shouldSearchCurrentFileFirst = when {
        // self:Method() -> 始终优先在当前文件查找
        prefixName == Constants.WORD_SELF -> true
        // LocalVar:Method() -> 仅当文件名是 LocalVar.lua 时优先在当前文件查找
        else -> {
            val fileNameWithoutExt = containingFile.virtualFile?.nameWithoutExtension 
                ?: containingFile.name.substringBeforeLast(".")
            fileNameWithoutExt == prefixName
        }
    }
    
    if (!shouldSearchCurrentFileFirst) {
        return null
    }
    
    // 在当前文件中查找方法定义
    return findMethodInCurrentFile(containingFile, prefixName, methodName)
}

/**
 * 在当前文件中查找方法定义
 * 查找形如 ClassName:methodName 或 ClassName.methodName 的方法定义
 */
private fun findMethodInCurrentFile(file: com.intellij.psi.PsiFile, className: String, methodName: String): PsiElement? {
    var result: PsiElement? = null
    
    // 遍历文件中的所有类方法定义
    PsiTreeUtil.processElements(file, LuaClassMethodDef::class.java) { methodDef ->
        val classMethodName = methodDef.classMethodName
        val expr = classMethodName.expr
        
        // 检查方法名是否匹配
        if (methodDef.name == methodName) {
            // 检查类名是否匹配
            val methodClassName = when (expr) {
                is LuaNameExpr -> expr.name
                else -> null
            }
            
            // 对于 self 调用，匹配当前文件中任何类方法
            // 对于 LocalVar 调用，需要类名完全匹配
            if (className == Constants.WORD_SELF || methodClassName == className) {
                result = methodDef
                return@processElements false // 找到后停止
            }
        }
        true // 继续查找
    }
    
    return result
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
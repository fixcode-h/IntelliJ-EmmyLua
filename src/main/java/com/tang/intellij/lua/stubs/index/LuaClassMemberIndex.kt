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

package com.tang.intellij.lua.stubs.index

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.IntStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexEx
import com.intellij.util.Processor
import com.intellij.util.containers.ContainerUtil
import com.tang.intellij.lua.comment.psi.LuaDocTagField
import com.tang.intellij.lua.psi.LuaClassMember
import com.tang.intellij.lua.psi.LuaClassMethod
import com.tang.intellij.lua.psi.LuaTableField
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.ITyClass
import com.tang.intellij.lua.ty.TyClass
import com.tang.intellij.lua.ty.TyParameter

class LuaClassMemberIndex : IntStubIndexExtension<LuaClassMember>() {
    override fun getKey() = StubKeys.CLASS_MEMBER

    @Deprecated("This method is deprecated in the parent class")
    override fun get(s: Int, project: Project, scope: GlobalSearchScope): Collection<LuaClassMember> {
        return try {
            val elements = safeGetStubElements(s, project, scope)
            // 过滤掉无效的元素，防止访问已失效的 PSI
            elements.filter { element ->
                try {
                    val containingFile = element.containingFile
                    containingFile != null && containingFile.isValid
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Throwable) {
            // 索引不同步时返回空集合（常见于外部工具修改文件的场景）
            // IntelliJ 会在后台自动重新索引，无需用户干预
            if (LOG.isDebugEnabled) {
                LOG.debug("Failed to get stub index for key=$s: ${e.message}")
            }
            emptyList()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(LuaClassMemberIndex::class.java)
        val instance = LuaClassMemberIndex()
        
        // 递归保护：追踪正在处理的类，避免无限递归
        private val processingClasses = ThreadLocal.withInitial { mutableSetOf<String>() }
        
        // 记录失败的 key，避免重复尝试访问损坏的索引
        private val failedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        
        // 记录失败的文件信息，用于诊断
        private val failedFiles = java.util.concurrent.ConcurrentHashMap<String, Int>()
        
        /**
         * 安全地调用 StubIndex.getElements，处理索引不一致的情况
         * 
         * 问题根源分析：
         * 1. Stub 反序列化失败时，LuaFileStub.deserialize() 返回空 stub
         * 2. 但索引键（key）已经在之前被写入
         * 3. 查询时找不到对应的 stub IDs → StubProcessingHelper 抛出异常并记录 ERROR 日志
         * 
         * 常见场景：
         * - UE Blueprint 生成的 Lua 文件（BP_XXX_C.lua）可能有格式问题
         * - 外部工具修改文件导致 stub 缓存失效
         * - 文件内容损坏或包含特殊字符
         * - 增量索引更新不完整
         * 
         * 解决方案：
         * 1. 捕获异常并返回空集合（避免中断用户操作）
         * 2. 使用 failedKeys 缓存避免重复查询同一个损坏的索引
         * 3. 建议用户使用 "Invalidate Caches" 重建索引
         * 
         * 注意：无法阻止 IntelliJ 内部的 Logger.error()，因为日志在异常抛出前就已记录
         */
        private fun safeGetStubElements(
            key: Int,
            project: Project,
            scope: GlobalSearchScope
        ): Collection<LuaClassMember> {
            return try {
                StubIndex.getElements(StubKeys.CLASS_MEMBER, key, project, scope, LuaClassMember::class.java)
            } catch (e: Throwable) {
                // 索引不一致或 stub 反序列化失败
                // 返回空集合，让代码继续执行
                // failedKeys 缓存会阻止后续对同一 key 的查询
                emptyList()
            }
        }

        private fun process(key: String, context: SearchContext, processor: Processor<LuaClassMember>): Boolean {
            if (context.isDumb)
                return false
            
            val keyHash = key.hashCode()
            
            // 如果这个 key 之前访问失败过，直接跳过避免重复尝试
            if (failedKeys.contains(keyHash)) {
                return true
            }
            
            // 访问 Stub 索引，捕获可能的异常
            try {
                val all = try {
                    safeGetStubElements(keyHash, context.project, context.scope)
                } catch (e: Throwable) {
                    // 索引不同步时的异常（常见于 UE Blueprint 自动生成的文件）
                    // 这是正常现象，IntelliJ 会自动重新索引
                    
                    // 记录详细诊断信息（仅在 DEBUG 模式下）
                    if (LOG.isDebugEnabled) {
                        LOG.debug("=== Stub Index Error Diagnostics ===")
                        LOG.debug("Key: $key (hash: $keyHash)")
                        LOG.debug("Project: ${context.project.name}")
                        LOG.debug("Exception: ${e.javaClass.simpleName}: ${e.message}")
                        
                        // 尝试从异常消息中提取文件信息
                        val exceptionMsg = e.message ?: e.toString()
                        val filePattern = Regex("file://([^,]+)")
                        val fileMatch = filePattern.find(exceptionMsg)
                        if (fileMatch != null) {
                            val filePath = fileMatch.groupValues[1]
                            val fileName = filePath.substringAfterLast('/')
                            LOG.debug("Problematic file: $fileName")
                            LOG.debug("Full path: $filePath")
                            
                            // 检查文件模式
                            when {
                                fileName.startsWith("BP_") && fileName.endsWith("_C.lua") -> {
                                    LOG.debug("Pattern: UE Blueprint generated file")
                                }
                                fileName.contains("_C.lua") -> {
                                    LOG.debug("Pattern: Possible C++ generated file")
                                }
                                else -> {
                                    LOG.debug("Pattern: Unknown")
                                }
                            }
                        }
                        
                        LOG.debug("Stack trace (first 3 frames):")
                        e.stackTrace.take(3).forEach { frame ->
                            LOG.debug("  at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
                        }
                        LOG.debug("====================================")
                    }
                    
                    // 统计失败文件（用于内部监控）
                    val exceptionMsg = e.message ?: e.toString()
                    val filePattern = Regex("file://([^,]+)")
                    val fileMatch = filePattern.find(exceptionMsg)
                    if (fileMatch != null) {
                        val filePath = fileMatch.groupValues[1]
                        val fileName = filePath.substringAfterLast('/')
                        failedFiles.merge(fileName, 1) { old, _ -> old + 1 }
                    }
                    
                    // 标记这个 key 为临时失败状态，避免重复访问
                    failedKeys.add(keyHash)
                    return true
                }
                
                // 过滤掉无效的元素
                val validElements = all.filter { element ->
                    try {
                        val containingFile = element.containingFile
                        containingFile != null && containingFile.isValid
                    } catch (e: Exception) {
                        false
                    }
                }
                
                return ContainerUtil.process(validElements, processor)
            } catch (e: Throwable) {
                // 外层异常处理（仅在 DEBUG 模式记录详细信息）
                if (LOG.isDebugEnabled) {
                    LOG.debug("Unexpected error processing key=$key: ${e.message}", e)
                }
                failedKeys.add(keyHash)
                return true
            }
        }
        
        /**
         * 清除失败的 key 缓存
         * 当索引更新完成后，这些 key 可能已经可以正常访问了
         */
        fun clearFailedKeys() {
            if (LOG.isDebugEnabled) {
                LOG.debug("Clearing failed keys cache (${failedKeys.size} entries)")
                if (failedFiles.isNotEmpty()) {
                    LOG.debug("Failed files summary:")
                    failedFiles.entries.sortedByDescending { it.value }.take(10).forEach { (file, count) ->
                        LOG.debug("  $file: $count times")
                    }
                }
            }
            failedKeys.clear()
            failedFiles.clear()
        }
        
        /**
         * 清除特定 key 的失败状态
         */
        fun clearFailedKey(key: Int) {
            failedKeys.remove(key)
        }
        
        /**
         * 定期清理失败缓存（后台任务）
         * 避免缓存持续增长
         */
        fun trimFailedKeys() {
            if (failedKeys.size > 1000) {
                failedKeys.clear()
            }
        }

        fun process(className: String, fieldName: String, context: SearchContext, processor: Processor<LuaClassMember>, deep: Boolean = true): Boolean {
            val key = "$className*$fieldName"
            if (!process(key, context, processor))
                return false

            if (deep) {
                // 递归保护：检查是否正在处理同一个类
                val processing = processingClasses.get()
                if (processing.contains(className)) {
                    // 避免递归调用导致栈溢出
                    return true
                }
                
                processing.add(className)
                try {
                    val classDef = LuaClassIndex.find(className, context)
                    if (classDef != null) {
                        val type = classDef.type
                        // from alias
                        type.lazyInit(context)
                        val notFound = type.processAlias(Processor {
                            process(it, fieldName, context, processor, false)
                        })
                        if (!notFound)
                            return false

                        // from supper
                        return TyClass.processSuperClass(type, context) {
                            process(it.className, fieldName, context, processor, false)
                        }
                    }
                    return true
                } finally {
                    processing.remove(className)
                }
            }
            return true
        }

        private fun find(type: ITyClass, fieldName: String, context: SearchContext): LuaClassMember? {
            var perfect: LuaClassMember? = null
            var tagField: LuaDocTagField? = null
            var tableField: LuaTableField? = null
            processAll(type, fieldName, context, Processor {
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
            })
            if (tagField != null) return tagField
            if (tableField != null) return tableField
            return perfect
        }

        private fun processAll(type: ITyClass, fieldName: String, context: SearchContext, processor: Processor<LuaClassMember>): Boolean {
            return if (type is TyParameter)
                type.superClassName?.let { process(it, fieldName, context, processor) } ?: true
            else process(type.className, fieldName, context, processor)
        }

        fun processAll(type: ITyClass, context: SearchContext, processor: Processor<LuaClassMember>): Boolean {
            // 递归保护
            val processing = processingClasses.get()
            val className = type.className
            if (processing.contains(className)) {
                return true
            }
            
            processing.add(className)
            try {
                if (process(className, context, processor)) {
                    type.lazyInit(context)
                    return type.processAlias(Processor {
                        process(it, context, processor)
                    })
                }
                return true
            } finally {
                processing.remove(className)
            }
        }

        private fun findMethod(className: String, memberName: String, context: SearchContext, deep: Boolean = true): LuaClassMethod? {
            var target: LuaClassMethod? = null
            process(className, memberName, context, Processor {
                if (it is LuaClassMethod) {
                    target = it
                    return@Processor false
                }
                true
            }, deep)
            return target
        }

        fun indexStub(indexSink: IndexSink, className: String, memberName: String) {
            indexSink.occurrence(StubKeys.CLASS_MEMBER, className.hashCode())
            indexSink.occurrence(StubKeys.CLASS_MEMBER, "$className*$memberName".hashCode())
        }
    }
}

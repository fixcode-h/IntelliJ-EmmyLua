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

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.IntStubIndexExtension
import com.intellij.psi.stubs.StubIndex
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
    override fun get(s: Int, project: Project, scope: GlobalSearchScope): Collection<LuaClassMember> =
            StubIndex.getElements(StubKeys.CLASS_MEMBER, s, project, scope, LuaClassMember::class.java)

    companion object {
        val instance = LuaClassMemberIndex()

        private fun process(key: String, context: SearchContext, processor: Processor<LuaClassMember>): Boolean {
            if (context.isDumb)
                return false
            
            // 缓存验证：在访问Stub数据前验证文件完整性
            try {
                val all = StubIndex.getElements(StubKeys.CLASS_MEMBER, key.hashCode(), context.project, context.scope, LuaClassMember::class.java)
                
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
            } catch (e: Exception) {
                // 如果访问Stub索引失败，返回true继续处理其他索引
                return true
            }
        }

        fun process(className: String, fieldName: String, context: SearchContext, processor: Processor<LuaClassMember>, deep: Boolean = true): Boolean {
            val key = "$className*$fieldName"
            if (!process(key, context, processor))
                return false

            if (deep) {
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
            if (process(type.className, context, processor)) {
                type.lazyInit(context)
                return type.processAlias(Processor {
                    process(it, context, processor)
                })
            }
            return true
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

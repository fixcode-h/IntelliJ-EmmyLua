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

package com.tang.intellij.lua.codeInsight.template.macro

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Macro
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TextResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.editor.completion.LuaLookupElement
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.psi.LuaLocalDef
import com.tang.intellij.lua.psi.LuaPsiTreeUtil
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.ITy
import com.tang.intellij.lua.ty.Ty
import com.tang.intellij.lua.ty.TyPrimitive
import java.util.*

/**
 * SmartSuggestTypeMacro - 智能类型推断宏
 * 根据赋值来源推断变量类型
 * Created by TangZX on 2016/12/16.
 */
class SmartSuggestTypeMacro : Macro() {
    override fun getName(): String {
        return "SmartSuggestTypeMacro"
    }

    override fun getPresentableName(): String {
        return "SmartSuggestTypeMacro"
    }

    override fun calculateResult(expressions: Array<Expression>, expressionContext: ExpressionContext): Result? {
        val psiFile = expressionContext.psiElementAtStartOffset?.containingFile ?: return null
        val offset = expressionContext.startOffset
        
        // 查找当前位置的 LuaLocalDef
        val localDef = LuaPsiTreeUtil.findElementOfClassAtOffset(psiFile, offset, LuaLocalDef::class.java, false)
        if (localDef != null) {
            val inferredType = inferTypeFromAssignment(localDef)
            if (inferredType != null && inferredType != Ty.UNKNOWN) {
                return TextResult(formatType(inferredType))
            }
        }
        
        return null
    }

    override fun calculateLookupItems(params: Array<Expression>, context: ExpressionContext): Array<LookupElement>? {
        val psiElement = context.psiElementAtStartOffset ?: return null
        
        // 使用缓存来避免重复计算
        return CachedValuesManager.getCachedValue(psiElement) {
            val list = ArrayList<LookupElement>()
            
            // 首先尝试从赋值推断类型
            val psiFile = psiElement.containingFile
            if (psiFile != null) {
                val offset = context.startOffset
                val localDef = LuaPsiTreeUtil.findElementOfClassAtOffset(psiFile, offset, LuaLocalDef::class.java, false)
                if (localDef != null) {
                    val inferredType = inferTypeFromAssignment(localDef)
                    if (inferredType != null && inferredType != Ty.UNKNOWN) {
                        val typeString = formatType(inferredType)
                        // 将推断的类型作为第一个选项
                        list.add(LookupElementBuilder.create(typeString).withIcon(LuaIcons.CLASS))
                    }
                }
            }
            
            // 添加其他常用类型，但限制数量以提高性能
            if (list.isEmpty() || list.size < 10) {
                val additionalTypes = ArrayList<LookupElement>()
                val maxAdditionalTypes = 15
                
                // 调用原始方法获取所有类型
                LuaLookupElement.fillTypes(context.project, additionalTypes)
                
                // 限制添加的类型数量以提高性能
                if (additionalTypes.size > maxAdditionalTypes) {
                    // 只取前面的类型，这样可以保证最常用的类型优先显示
                    val limitedTypes = additionalTypes.take(maxAdditionalTypes)
                    list.addAll(limitedTypes)
                } else {
                    list.addAll(additionalTypes)
                }
            }
            
            CachedValueProvider.Result.create(
                if (list.isNotEmpty()) list.toTypedArray() else null,
                PsiModificationTracker.MODIFICATION_COUNT
            )
        }
    }
    
    /**
     * 从赋值语句推断类型
     */
    private fun inferTypeFromAssignment(localDef: LuaLocalDef): ITy? {
        // 使用缓存来避免重复的类型推断计算
        return CachedValuesManager.getCachedValue(localDef) {
            val exprList = localDef.exprList
            val nameList = localDef.nameList
            
            // 早期返回，避免不必要的计算
            if (exprList == null || nameList == null) {
                return@getCachedValue CachedValueProvider.Result.create(null, PsiModificationTracker.MODIFICATION_COUNT)
            }
            
            // 获取变量名列表
            val nameDefList = nameList.nameDefList
            if (nameDefList.isEmpty()) {
                return@getCachedValue CachedValueProvider.Result.create(null, PsiModificationTracker.MODIFICATION_COUNT)
            }
            
            // 获取表达式列表
            val expressions = exprList.exprList
            if (expressions.isEmpty()) {
                return@getCachedValue CachedValueProvider.Result.create(null, PsiModificationTracker.MODIFICATION_COUNT)
            }
            
            // 创建搜索上下文
            val searchContext = SearchContext.get(localDef.project)
            
            // 对于第一个变量，尝试推断其类型
            val firstExpr = expressions.firstOrNull()
            val inferredType = if (firstExpr != null) {
                try {
                    firstExpr.guessType(searchContext)
                } catch (e: Exception) {
                    // 如果类型推断出现异常，返回null而不是崩溃
                    null
                }
            } else {
                null
            }
            
            CachedValueProvider.Result.create(inferredType, PsiModificationTracker.MODIFICATION_COUNT)
        }
    }
    
    /**
     * 格式化类型字符串
     */
    private fun formatType(type: ITy): String {
        return when {
            type is TyPrimitive -> type.displayName
            type.displayName.isNotEmpty() -> type.displayName
            else -> "table"
        }
    }
}
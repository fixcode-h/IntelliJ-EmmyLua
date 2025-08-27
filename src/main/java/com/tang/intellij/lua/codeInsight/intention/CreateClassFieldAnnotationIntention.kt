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

package com.tang.intellij.lua.codeInsight.intention

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.comment.LuaCommentUtil
import com.tang.intellij.lua.comment.psi.LuaDocTagClass
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.Ty
import com.tang.intellij.lua.ty.TyUnion
import com.tang.intellij.lua.ty.TyUnknown

/**
 * 为类变量创建字段注释的意图动作
 * 当鼠标在local变量上且该变量在文件末尾被返回时，扫描文件中的self字段并生成类型注释
 */
class CreateClassFieldAnnotationIntention : BaseIntentionAction() {

    override fun getFamilyName(): String {
        return text
    }

    override fun getText(): String {
        return "Create class field annotations"
    }

    override fun isAvailable(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
        val localDef = LuaPsiTreeUtil.findElementOfClassAtOffset(
            psiFile, 
            editor.caretModel.offset, 
            LuaLocalDef::class.java, 
            false
        )
        
        if (localDef != null && psiFile is LuaPsiFile) {
            val varName = localDef.nameList?.nameDefList?.firstOrNull()?.name
            if (varName != null) {
                // 检查文件末尾是否返回该变量
                return isVariableReturnedAtFileEnd(psiFile, varName)
            }
        }
        return false
    }

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        val localDef = LuaPsiTreeUtil.findElementOfClassAtOffset(
            psiFile, 
            editor.caretModel.offset, 
            LuaLocalDef::class.java, 
            false
        )
        
        if (localDef != null) {
            val varName = localDef.nameList?.nameDefList?.firstOrNull()?.name
            if (varName != null) {
                generateClassAnnotations(project, editor, psiFile, localDef, varName)
            }
        }
    }

    /**
     * 检查变量是否在文件末尾被返回
     */
    private fun isVariableReturnedAtFileEnd(psiFile: PsiFile, varName: String): Boolean {
        val luaFile = psiFile as? LuaPsiFile ?: return false
        
        var lastStatement: PsiElement? = null
        var child = luaFile.firstChild
        while (child != null) {
            if (child is LuaStatement) {
                lastStatement = child
            }
            child = child.nextSibling
        }
        
        if (lastStatement is LuaReturnStat) {
            val returnExpr = lastStatement.exprList?.exprList?.firstOrNull()
            if (returnExpr is LuaNameExpr) {
                return returnExpr.name == varName
            }
        }
        return false
    }

    /**
     * 生成类注释和字段注释
     */
    private fun generateClassAnnotations(
        project: Project, 
        editor: Editor, 
        psiFile: PsiFile, 
        localDef: LuaLocalDef, 
        className: String
    ) {
        // 扫描文件中的self字段
        val selfFields = scanSelfFields(psiFile)
        
        // 检查是否已存在@class注释（安全检查）
        val hasClassAnnotation = try {
            val existingComment = localDef.comment
            existingComment?.findTag(LuaDocTagClass::class.java) != null
        } catch (e: Exception) {
            // 如果检查失败，假设没有@class注释
            false
        }
        
        // 生成注释内容
        val annotationText = buildAnnotationText(className, selfFields, hasClassAnnotation)
        
        // 插入注释
        LuaCommentUtil.insertTemplate(localDef, editor) { _, template ->
            template.addTextSegment(annotationText)
            template.addEndVariable()
        }
    }

    /**
     * 扫描文件中的self字段
     */
    private fun scanSelfFields(psiFile: PsiFile): List<SelfField> {
        val fields = mutableListOf<SelfField>()
        val luaFile = psiFile as? LuaPsiFile ?: return fields
        
        // 递归遍历文件中的所有赋值语句
        PsiTreeUtil.processElements(luaFile, LuaAssignStat::class.java) { assignStat ->
            val varList = assignStat.varExprList?.exprList
            val valueList = assignStat.valueExprList?.exprList
            
            varList?.forEachIndexed { index, varExpr ->
                if (varExpr is LuaIndexExpr) {
                    val prefixExpr = varExpr.prefixExpr
                    if (prefixExpr is LuaNameExpr && prefixExpr.name == "self") {
                        val fieldName = varExpr.name
                        if (fieldName != null) {
                            val fieldType = if (index < (valueList?.size ?: 0)) {
                                inferFieldType(valueList!![index])
                            } else {
                                ""
                            }
                            fields.add(SelfField(fieldName, fieldType))
                        }
                    }
                }
            }
            true // 继续遍历
        }
        
        // 去重
        return fields.distinctBy { it.name }
    }

    /**
     * 推断字段类型
     */
    private fun inferFieldType(expr: LuaExpr): String {
        try {
            val context = SearchContext.get(expr.project)
            val type = expr.guessType(context)
            
            return when {
                type is TyUnion -> {
                    type.displayName
                }
                type != null && type !is TyUnknown -> {
                    type.displayName
                }
                else -> ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * 构建注释文本
     */
    private fun buildAnnotationText(
        className: String, 
        fields: List<SelfField>, 
        hasClassAnnotation: Boolean
    ): String {
        val sb = StringBuilder()
        
        // 添加@class注释（如果不存在）
        if (!hasClassAnnotation) {
            sb.append("---@class $className\n")
        }
        
        // 添加@field注释
        fields.forEachIndexed { index, field ->
            sb.append("---@field ${field.name}")
            if (field.type.isNotEmpty()) {
                sb.append(" ${field.type}")
            }
            // 只在不是最后一个字段时添加换行符
            if (index < fields.size - 1) {
                sb.append("\n")
            }
        }
        
        return sb.toString()
    }

    /**
     * 表示self字段的数据类
     */
    private data class SelfField(
        val name: String,
        val type: String
    )
}
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

package com.tang.intellij.lua.codeInsight.inspection

import com.intellij.codeInspection.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringFactory
import com.intellij.util.Processor
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.comment.psi.LuaDocPsiElement
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext
import org.jetbrains.annotations.Nls

/**
 *
 * Created by TangZX on 2017/2/8.
 */
class RemoveUnusedLocal : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        return object : LuaVisitor() {

            override fun visitParamNameDef(o: LuaParamNameDef) {
                if (o.textMatches(Constants.WORD_UNDERLINE))
                    return
                
                // 使用扩展的搜索范围
                val searchScope = getLocalVariableSearchScope(o)
                val search = ReferencesSearch.search(o, searchScope)
                var found = false
                search.forEach(Processor { reference ->
                    if (reference.element !is LuaDocPsiElement) {
                        found = true
                    }
                    !found
                })
                
                // 如果没找到引用，进行二次验证
                if (!found) {
                    found = hasManualParameterReferences(o)
                }
                
                if (!found) {
                    holder.registerProblem(o,
                            "Unused parameter : '${o.name}'",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                            RenameToUnderlineFix())
                }
            }

            override fun visitLocalDef(o: LuaLocalDef) {
                val list = o.nameList?.nameDefList ?: return
                list.forEach { name ->
                    if (!name.textMatches(Constants.WORD_UNDERLINE)) {
                        // 使用扩展的搜索范围和二次验证
                        val searchScope = getLocalVariableSearchScope(name)
                        val search = ReferencesSearch.search(name, searchScope)
                        
                        if (search.findFirst() == null) {
                            // 进行二次验证：手动遍历AST查找引用
                            if (!hasManualReferences(name)) {
                                if (list.size == 1) {
                                    val offset = name.node.startOffset - o.node.startOffset
                                    val textRange = TextRange(offset, offset + name.textLength)
                                    holder.registerProblem(o,
                                            "Unused local : '${name.text}'",
                                            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                                            textRange,
                                            RemoveFix("Remove unused local '${name.text}'"),
                                            RenameToUnderlineFix())
                                } else {
                                    holder.registerProblem(name,
                                            "Unused local : '${name.text}'",
                                            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                                            RenameToUnderlineFix())
                                }
                            }
                        }
                    }
                }
            }

            override fun visitLocalFuncDef(o: LuaLocalFuncDef) {
                val name = o.nameIdentifier

                if (name != null) {
                    // 使用扩展的搜索范围
                    val searchScope = getLocalVariableSearchScope(o)
                    val search = ReferencesSearch.search(o, searchScope)
                    
                    if (search.findFirst() == null) {
                        // 进行二次验证：手动查找函数引用
                        if (!hasManualFunctionReferences(o)) {
                            val offset = name.node.startOffset - o.node.startOffset
                            val textRange = TextRange(offset, offset + name.textLength)

                            holder.registerProblem(o,
                                    "Unused local function : '${name.text}'",
                                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                                    textRange,
                                    RemoveFix("Remove unused local function : '${name.text}'"))
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取本地变量的正确搜索范围
     * 扩展到包含的函数体或文件级别，而不仅仅是文件范围
     */
    private fun getLocalVariableSearchScope(element: com.intellij.psi.PsiElement): SearchScope {
        // 找到包含的函数体、块或最外层作用域
        val containingScope = PsiTreeUtil.getParentOfType(element, 
            LuaFuncBody::class.java, LuaBlock::class.java) 
            ?: element.containingFile
        return LocalSearchScope(containingScope)
    }

    /**
     * 手动遍历AST查找引用，作为ReferencesSearch的补充验证
     * 这可以捕获一些引用解析机制可能遗漏的情况
     */
    private fun hasManualReferences(nameDef: LuaNameDef): Boolean {
        val name = nameDef.name
        val containingScope = PsiTreeUtil.getParentOfType(nameDef, 
            LuaFuncBody::class.java) ?: nameDef.containingFile
        
        var found = false
        containingScope.accept(object : LuaRecursiveVisitor() {
            override fun visitNameExpr(o: LuaNameExpr) {
                if (!found && o.name == name && o != nameDef) {
                    // 使用位置和作用域来验证引用关系，避免依赖可能不准确的解析
                    if (isValidReference(nameDef, o)) {
                        found = true
                        return
                    }
                }
                if (!found) {
                    super.visitNameExpr(o)
                }
            }
            
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (!found) {
                    super.visitElement(element)
                }
            }
        })
        return found
    }

    /**
     * 验证引用是否有效，基于位置和作用域规则
     */
    private fun isValidReference(definition: LuaNameDef, reference: LuaNameExpr): Boolean {
        // 引用必须在定义之后
        if (reference.textOffset <= definition.textOffset) {
            return false
        }
        
        // 检查作用域：引用必须在定义的可见范围内
        val defScope = findDeclarationScope(definition)
        val refScope = findDeclarationScope(reference)
        
        // 如果在同一个作用域或引用在定义的子作用域中
        return defScope != null && (defScope == refScope || isInChildScope(defScope, reference))
    }

    /**
     * 查找元素所在的声明作用域
     */
    private fun findDeclarationScope(element: com.intellij.psi.PsiElement): com.intellij.psi.PsiElement? {
        return PsiTreeUtil.getParentOfType(element, 
            LuaFuncBody::class.java, 
            LuaBlock::class.java,
            LuaDoStat::class.java,
            LuaForAStat::class.java,
            LuaForBStat::class.java,
            LuaRepeatStat::class.java,
            LuaWhileStat::class.java,
            LuaIfStat::class.java
        ) ?: element.containingFile
    }

    /**
     * 检查引用是否在定义作用域的子作用域中
     */
    private fun isInChildScope(defScope: com.intellij.psi.PsiElement, reference: com.intellij.psi.PsiElement): Boolean {
        var current: com.intellij.psi.PsiElement? = reference.parent
        while (current != null && current != defScope.containingFile) {
            if (current == defScope) {
                return true
            }
            current = current.parent
        }
        return false
    }

    /**
     * 手动查找参数引用的特殊版本
     */
    private fun hasManualParameterReferences(paramDef: LuaParamNameDef): Boolean {
        val name = paramDef.name
        val containingFunc = PsiTreeUtil.getParentOfType(paramDef, LuaFuncBody::class.java)
            ?: return false
        
        var found = false
        containingFunc.accept(object : LuaRecursiveVisitor() {
            override fun visitNameExpr(o: LuaNameExpr) {
                if (!found && o.name == name && o != paramDef) {
                    // 对于参数，检查是否在同一个函数作用域内且在参数定义之后
                    val exprFunc = PsiTreeUtil.getParentOfType(o, LuaFuncBody::class.java)
                    if (exprFunc == containingFunc && o.textOffset > paramDef.textOffset) {
                        found = true
                        return
                    }
                }
                if (!found) {
                    super.visitNameExpr(o)
                }
            }
            
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (!found) {
                    super.visitElement(element)
                }
            }
        })
        return found
    }

    /**
     * 手动查找本地函数引用
     */
    private fun hasManualFunctionReferences(funcDef: LuaLocalFuncDef): Boolean {
        val funcName = funcDef.name ?: return false
        val containingScope = PsiTreeUtil.getParentOfType(funcDef, 
            LuaFuncBody::class.java) ?: funcDef.containingFile
        
        var found = false
        containingScope.accept(object : LuaRecursiveVisitor() {
            override fun visitNameExpr(o: LuaNameExpr) {
                if (!found && o.name == funcName && o != funcDef) {
                    // 使用位置和作用域验证，避免依赖解析
                    if (isValidFunctionReference(funcDef, o)) {
                        found = true
                        return
                    }
                }
                if (!found) {
                    super.visitNameExpr(o)
                }
            }
            
            override fun visitCallExpr(o: LuaCallExpr) {
                if (!found) {
                    // 检查函数调用表达式
                    val expr = o.expr
                    if (expr is LuaNameExpr && expr.name == funcName) {
                        if (isValidFunctionReference(funcDef, expr)) {
                            found = true
                            return
                        }
                    }
                    super.visitCallExpr(o)
                }
            }
            
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (!found) {
                    super.visitElement(element)
                }
            }
        })
        return found
    }

    /**
     * 验证函数引用是否有效
     */
    private fun isValidFunctionReference(funcDef: LuaLocalFuncDef, reference: LuaNameExpr): Boolean {
        // 引用必须在函数定义之后
        if (reference.textOffset <= funcDef.textOffset) {
            return false
        }
        
        // 检查作用域：引用必须在函数定义的可见范围内
        val defScope = findDeclarationScope(funcDef)
        
        // 如果在同一个作用域或引用在定义的子作用域中
        return defScope != null && isInChildScope(defScope, reference)
    }

    private inner class RenameToUnderlineFix : LocalQuickFix {
        override fun getFamilyName() = "Rename to '${Constants.WORD_UNDERLINE}'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            ApplicationManager.getApplication().invokeLater {
                val factory = RefactoringFactory.getInstance(project)
                val refactoring = factory.createRename(descriptor.psiElement, Constants.WORD_UNDERLINE, false, false)
                refactoring.run()
            }
        }
    }

    private inner class RemoveFix(private val familyName: String) : LocalQuickFix {

        @Nls
        override fun getFamilyName() = familyName

        override fun applyFix(project: Project, problemDescriptor: ProblemDescriptor) {
            val element = problemDescriptor.endElement
            element.delete()
        }
    }
}

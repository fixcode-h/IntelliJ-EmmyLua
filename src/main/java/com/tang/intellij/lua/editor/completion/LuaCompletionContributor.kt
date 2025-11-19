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

package com.tang.intellij.lua.editor.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.tang.intellij.lua.comment.LuaCommentUtil
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.refactoring.LuaRefactoringUtil

/**
 * Lua 代码补全贡献者
 * 
 * Created by tangzx on 2016/11/27.
 * 
 * 负责在不同的位置提供相应的代码补全建议：
 * - 变量名、函数名、关键字
 * - 类成员、方法
 * - 文件路径（require）
 * - 等等
 */
class LuaCompletionContributor : CompletionContributor() {
    /** 是否启用文件内单词补全 */
    private var suggestWords = true
    
    init {
        // ========== 类成员补全 ==========
        
        // obj.member 或 obj:method 的成员补全
        extend(CompletionType.BASIC, SHOW_CLASS_FIELD, ClassMemberCompletionProvider())
        
        // 类方法内部的 self 成员提示
        extend(CompletionType.BASIC, IN_CLASS_METHOD, SuggestSelfMemberProvider())
        
        // 可以 override 的父类方法
        extend(CompletionType.BASIC, SHOW_OVERRIDE, OverrideCompletionProvider())
        
        
        // ========== 变量和函数补全 ==========
        
        // 普通表达式位置：全局函数、全局变量、local 变量、local 函数、关键字
        extend(CompletionType.BASIC, IN_NAME_EXPR, 
               LocalAndGlobalCompletionProvider(LocalAndGlobalCompletionProvider.ALL))
        
        // 方法名定义位置：只提示变量名（不提示关键字）
        extend(CompletionType.BASIC, IN_CLASS_METHOD_NAME, 
               LocalAndGlobalCompletionProvider(LocalAndGlobalCompletionProvider.VARS))
        
        // local 变量名定义位置：根据类型推断建议变量名
        extend(CompletionType.BASIC, psiElement(LuaTypes.ID).withParent(LuaNameDef::class.java), 
               SuggestLocalNameProvider())
        
        
        // ========== 特殊场景补全 ==========
        
        // require() 等函数的路径补全
        extend(CompletionType.BASIC, SHOW_REQUIRE_PATH, RequirePathCompletionProvider())
        
        // 字符串参数历史记录补全
        extend(CompletionType.BASIC, LuaStringArgHistoryProvider.STRING_ARG, LuaStringArgHistoryProvider())
        
        // goto 标签补全
        extend(CompletionType.BASIC, GOTO, object : CompletionProvider<CompletionParameters>(){
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, resultSet: CompletionResultSet) {
                LuaPsiTreeUtil.walkUpLabel(parameters.position) {
                    val name = it.name
                    if (name != null) {
                        resultSet.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Actions.Rollback))
                    }
                    return@walkUpLabel true
                }
                resultSet.stopHere()
            }
        })
        
        // table 字段名补全
        extend(CompletionType.BASIC, IN_TABLE_FIELD, TableCompletionProvider())
        
        // 变量属性补全（Lua 5.4 的 <const>, <close>）
        extend(CompletionType.BASIC, ATTRIBUTE, AttributeCompletionProvider())
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val session = CompletionSession(parameters, result)
        parameters.editor.putUserData(CompletionSession.KEY, session)
        super.fillCompletionVariants(parameters, result)
        if (LuaSettings.instance.isShowWordsInFile && suggestWords && session.isSuggestWords && !result.isStopped) {
            suggestWordsInFile(parameters)
        }
    }

    override fun beforeCompletion(context: CompletionInitializationContext) {
        suggestWords = true
        val file = context.file
        if (file is LuaPsiFile) {
            val element = file.findElementAt(context.caret.offset - 1)
            if (element != null) {
                if (element.parent is LuaLabelStat) {
                    suggestWords = false
                    context.dummyIdentifier = ""
                } else if (!LuaCommentUtil.isComment(element)) {
                    val type = element.node.elementType
                    if (type in IGNORE_SET) {
                        suggestWords = false
                        context.dummyIdentifier = ""
                    }
                    // 禁用在 = 号后面的单词补全
                    // local a = |  -- 这里不要从文件中提取单词补全
                    if (type == LuaTypes.ASSIGN) {
                        suggestWords = false
                    }
                    
                    // 检查是否在 local 变量名定义位置
                    // local demo = ...  -- demo 不应该有补全
                    if (type == LuaTypes.ID && isInLocalVariableNamePosition(element)) {
                        suggestWords = false
                        context.dummyIdentifier = ""
                    }
                }
            }
        }
    }
    
    /**
     * 检查元素是否在 local 变量名定义位置
     * 
     * 通过向前扫描 token 来判断：
     * - 如果遇到 local 关键字（且中间没有 =） → 是变量名定义位置
     * - 如果遇到 = 号 → 不是（在赋值右侧）
     * 
     * 示例：
     * - local demo      → 是（遇到 local）
     * - local a, b      → 是（遇到 local）
     * - local a = b     → a 是，b 不是（遇到 =）
     */
    private fun isInLocalVariableNamePosition(element: PsiElement): Boolean {
        var prev = element
        while (true) {
            prev = PsiTreeUtil.prevLeaf(prev) ?: break
            val prevType = prev.node.elementType
            
            // 遇到 local 关键字 → 是变量名定义位置
            if (prevType == LuaTypes.LOCAL) {
                return true
            }
            
            // 遇到 = 号 → 不是变量名定义位置（在赋值右侧）
            if (prevType == LuaTypes.ASSIGN) {
                return false
            }
            
            // 遇到逗号、标识符、空白，继续向前查找
            // 可能是多变量定义：local a, b, c = ...
            if (prevType == LuaTypes.COMMA || 
                prevType == LuaTypes.ID || 
                prevType == TokenType.WHITE_SPACE) {
                continue
            }
            
            // 遇到其他 token，停止（不是 local 定义）
            break
        }
        
        return false
    }

    companion object {
        // ============================================================================
        // 常量定义
        // ============================================================================
        
        /** 需要完全忽略补全的 token 类型（字符串、数字、连接符） */
        private val IGNORE_SET = TokenSet.create(LuaTypes.STRING, LuaTypes.NUMBER, LuaTypes.CONCAT)
        
        
        // ============================================================================
        // Completion Patterns - 定义在何时何地触发代码补全
        // ============================================================================
        
        // ---------- 类成员补全（点号/冒号访问） ----------
        
        /** 匹配类成员访问：obj.member 或 obj:method */
        private val SHOW_CLASS_FIELD = psiElement(LuaTypes.ID)
                .withParent(LuaIndexExpr::class.java)
        
        
        // ---------- 方法名补全 ----------
        
        /** 匹配在函数名定义位置 */
        private val IN_FUNC_NAME = psiElement(LuaTypes.ID)
                .withParent(LuaIndexExpr::class.java)
                .inside(LuaClassMethodName::class.java)
        
        /** 匹配 function 关键字后面 */
        private val AFTER_FUNCTION = psiElement()
                .afterLeaf(psiElement(LuaTypes.FUNCTION))
        
        /** 组合：方法名定义位置（函数名或 function 关键字后） */
        private val IN_CLASS_METHOD_NAME = psiElement().andOr(IN_FUNC_NAME, AFTER_FUNCTION)
        
        
        // ---------- 变量名/表达式补全 ----------
        
        /**
         * 匹配普通变量名和表达式位置
         * 
         * 排除规则：
         * 1. 在 NAME_LIST 内的变量名（PSI 树已构建，明确在 local 定义的变量名位置）
         * 
         * 注意：不排除其他情况，保证补全的可用性
         * 对于 PSI 树未完全构建的情况（如用户刚输入 local demo），
         * 会显示补全列表，用户可以选择使用或按 ESC 关闭
         * 
         * 示例：
         * - local some = value  → "some" 可能有补全（PSI 未构建时），"value" 补全 ✓
         * - local a, b = c, d   → "a", "b" 不补全（NAME_LIST 内），"c", "d" 补全 ✓
         * - demo = UE4.AActor   → 全部补全 ✓
         * - for/if/while 等     → 关键字补全 ✓
         */
        private val IN_NAME_EXPR = psiElement(LuaTypes.ID)
                .withParent(LuaNameExpr::class.java)
                .andNot(
                    // 只排除：明确在 NAME_LIST 内的（PSI 树已完全构建的情况）
                    psiElement()
                        .inside(psiElement(LuaTypes.NAME_LIST))
                        .inside(LuaLocalDef::class.java)
                )
        
        
        // ---------- 其他特殊场景 ----------
        
        /** 匹配可以 override 的方法名位置 */
        private val SHOW_OVERRIDE = psiElement()
                .withParent(LuaClassMethodName::class.java)
        
        /** 匹配类方法内部的表达式（用于 self 成员提示） */
        private val IN_CLASS_METHOD = psiElement(LuaTypes.ID)
                .withParent(LuaNameExpr::class.java)
                .inside(LuaClassMethodDef::class.java)
        
        /** 匹配 require() 等函数的字符串参数（路径补全） */
        private val SHOW_REQUIRE_PATH = psiElement(LuaTypes.STRING)
                .withParent(
                        psiElement(LuaTypes.LITERAL_EXPR).withParent(
                                psiElement(LuaArgs::class.java).afterSibling(
                                        psiElement().with(RequireLikePatternCondition())
                                )
                        )
                )
        
        /** 匹配 goto 语句中的标签名 */
        private val GOTO = psiElement(LuaTypes.ID).withParent(LuaGotoStat::class.java)
        
        /** 匹配 table 字段名位置 */
        private val IN_TABLE_FIELD = psiElement().andOr(
                psiElement().withParent(
                        psiElement(LuaTypes.NAME_EXPR).withParent(LuaTableField::class.java)
                ),
                psiElement(LuaTypes.ID).withParent(LuaTableField::class.java)
        )
        
        /** 匹配变量属性（如 local a <close>） */
        private val ATTRIBUTE = psiElement(LuaTypes.ID).withParent(LuaAttribute::class.java)

        private fun suggestWordsInFile(parameters: CompletionParameters) {
            val session = CompletionSession[parameters]
            val originalPosition = parameters.originalPosition
            if (originalPosition != null)
                session.addWord(originalPosition.text)
            
            val wordsScanner = LanguageFindUsages.INSTANCE.forLanguage(LuaLanguage.INSTANCE).wordsScanner
            wordsScanner?.processWords(parameters.editor.document.charsSequence) {
                val word = it.baseText.subSequence(it.start, it.end).toString()
                if (word.length > 2 && LuaRefactoringUtil.isLuaIdentifier(word) && session.addWord(word)) {
                    session.resultSet.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder
                            .create(word)
                            .withIcon(LuaIcons.WORD), -1.0)
                    )
                }
                true
            }
        }
    }
}

class RequireLikePatternCondition : PatternCondition<PsiElement>("requireLike"){
    override fun accepts(psi: PsiElement, context: ProcessingContext?): Boolean {
        val name = (psi as? PsiNamedElement)?.name
        return if (name != null) LuaSettings.isRequireLikeFunctionName(name) else false
    }
}
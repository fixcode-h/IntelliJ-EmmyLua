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
import com.intellij.psi.tree.TokenSet
import com.intellij.util.ProcessingContext
import com.tang.intellij.lua.comment.LuaCommentUtil
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.refactoring.LuaRefactoringUtil

/**

 * Created by tangzx on 2016/11/27.
 */
class LuaCompletionContributor : CompletionContributor() {
    private var suggestWords = true
    init {
        //可以override
        extend(CompletionType.BASIC, SHOW_OVERRIDE, OverrideCompletionProvider())

        extend(CompletionType.BASIC, IN_CLASS_METHOD, SuggestSelfMemberProvider())

        //提示属性, 提示方法
        extend(CompletionType.BASIC, SHOW_CLASS_FIELD, ClassMemberCompletionProvider())

        extend(CompletionType.BASIC, SHOW_REQUIRE_PATH, RequirePathCompletionProvider())

        extend(CompletionType.BASIC, LuaStringArgHistoryProvider.STRING_ARG, LuaStringArgHistoryProvider())

        //提示全局函数,local变量,local函数
        extend(CompletionType.BASIC, IN_NAME_EXPR, LocalAndGlobalCompletionProvider(LocalAndGlobalCompletionProvider.ALL))

        extend(CompletionType.BASIC, IN_CLASS_METHOD_NAME, LocalAndGlobalCompletionProvider(LocalAndGlobalCompletionProvider.VARS))

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

        extend(CompletionType.BASIC, psiElement(LuaTypes.ID).withParent(LuaNameDef::class.java), SuggestLocalNameProvider())

        extend(CompletionType.BASIC, IN_TABLE_FIELD, TableCompletionProvider())

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
                    // 检查是否在 local 关键字后面（定义变量名）
                    // local Demo|  -- 这里应该禁用补全
                    var prevLeaf = com.intellij.psi.util.PsiTreeUtil.prevLeaf(element, true)
                    while (prevLeaf != null && prevLeaf.node.elementType == com.intellij.psi.TokenType.WHITE_SPACE) {
                        prevLeaf = com.intellij.psi.util.PsiTreeUtil.prevLeaf(prevLeaf, true)
                    }
                    // 如果前面是 local 或者在 local 定义的变量列表中
                    if (prevLeaf != null && prevLeaf.node.elementType == LuaTypes.LOCAL) {
                        // 完全禁用补全
                        context.dummyIdentifier = ""
                        return
                    }
                    // 检查是否在 local 变量列表中的逗号后面
                    // local a, Demo|
                    if (prevLeaf != null && prevLeaf.node.elementType == LuaTypes.COMMA) {
                        // 向上查找是否在 LocalDef 中
                        val parent = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, LuaLocalDef::class.java)
                        if (parent != null) {
                            context.dummyIdentifier = ""
                            return
                        }
                    }
                    // 禁用在 = 号后面的单词补全
                    // local a = |  -- 这里不要从文件中提取单词补全
                    if (type == LuaTypes.ASSIGN) {
                        suggestWords = false
                    }
                }
            }
        }
    }

    companion object {
        private val IGNORE_SET = TokenSet.create(LuaTypes.STRING, LuaTypes.NUMBER, LuaTypes.CONCAT)

        private val SHOW_CLASS_FIELD = psiElement(LuaTypes.ID)
                .withParent(LuaIndexExpr::class.java)

        private val IN_FUNC_NAME = psiElement(LuaTypes.ID)
                .withParent(LuaIndexExpr::class.java)
                .inside(LuaClassMethodName::class.java)
        private val AFTER_FUNCTION = psiElement()
                .afterLeaf(psiElement(LuaTypes.FUNCTION))
        private val IN_CLASS_METHOD_NAME = psiElement().andOr(IN_FUNC_NAME, AFTER_FUNCTION)

        // 排除在定义局部变量名称时的补全（但保留在赋值表达式中的补全）
        // local some = value  -- "some" 不补全，"value" 需要补全
        // local a, b = c, d   -- "a", "b" 不补全，"c", "d" 需要补全
        private val IN_NAME_EXPR = psiElement(LuaTypes.ID)
                .withParent(LuaNameExpr::class.java)
                .andNot(
                    // 方式1：检查是否在 NAME_LIST 中且在 LuaLocalDef 中
                    psiElement()
                        .inside(psiElement(LuaTypes.NAME_LIST))
                        .inside(LuaLocalDef::class.java)
                ).andNot(
                    // 方式2：检查前面是否直接跟着 local 关键字（处理输入时 PSI 未完全构建的情况）
                    psiElement()
                        .afterLeafSkipping(
                            psiElement().whitespace(),  // 跳过空格
                            psiElement(LuaTypes.LOCAL)   // 前面是 local 关键字
                        )
                ).andNot(
                    // 方式3：检查前面是否是逗号（在 local a, b 中输入 b 时）
                    psiElement()
                        .afterLeafSkipping(
                            psiElement().whitespace(),
                            psiElement(LuaTypes.COMMA)
                        )
                        .inside(LuaLocalDef::class.java)
                )

        private val SHOW_OVERRIDE = psiElement()
                .withParent(LuaClassMethodName::class.java)
        private val IN_CLASS_METHOD = psiElement(LuaTypes.ID)
                .withParent(LuaNameExpr::class.java)
                .inside(LuaClassMethodDef::class.java)
        private val SHOW_REQUIRE_PATH = psiElement(LuaTypes.STRING)
                .withParent(
                        psiElement(LuaTypes.LITERAL_EXPR).withParent(
                                psiElement(LuaArgs::class.java).afterSibling(
                                        psiElement().with(RequireLikePatternCondition())
                                )
                        )
                )

        private val GOTO = psiElement(LuaTypes.ID).withParent(LuaGotoStat::class.java)

        private val IN_TABLE_FIELD = psiElement().andOr(
                psiElement().withParent(
                        psiElement(LuaTypes.NAME_EXPR).withParent(LuaTableField::class.java)
                ),
                psiElement(LuaTypes.ID).withParent(LuaTableField::class.java)
        )

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
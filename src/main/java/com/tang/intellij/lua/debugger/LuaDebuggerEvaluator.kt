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

package com.tang.intellij.lua.debugger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.tang.intellij.lua.psi.*

/**
 *
 * Created by tangzx on 2017/5/1.
 */
abstract class LuaDebuggerEvaluator : XDebuggerEvaluator() {
    override fun getExpressionRangeAtOffset(project: Project, document: Document, offset: Int, sideEffectsAllowed: Boolean): TextRange? {
        var currentRange: TextRange? = null
        
        val runnable = lambda@ {
            try {
                // 确保文档已提交
                val psiDocumentManager = PsiDocumentManager.getInstance(project)
                if (psiDocumentManager.isUncommited(document)) {
                    psiDocumentManager.commitDocument(document)
                }
                
                val file = psiDocumentManager.getPsiFile(document) ?: return@lambda
                if (currentRange == null) {
                    val ele = file.findElementAt(offset)
                    if (ele != null && ele.node.elementType == LuaTypes.ID) {
                        val parent = ele.parent
                        when (parent) {
                            is LuaFuncDef,
                            is LuaLocalFuncDef -> currentRange = ele.textRange
                            is LuaClassMethodName,
                            is PsiNameIdentifierOwner -> currentRange = parent.textRange
                        }
                    }
                }

                if (currentRange == null) {
                    val expr = PsiTreeUtil.findElementOfClassAtOffset(file, offset, LuaExpr::class.java, false)
                    currentRange = when (expr) {
                        is LuaCallExpr,
                        is LuaClosureExpr,
                        is LuaLiteralExpr -> null
                        else -> expr?.textRange
                    }
                }
            } catch (ignored: IndexNotReadyException) {
            }
        }
        
        // 检查是否已经在ReadAction中
        if (ApplicationManager.getApplication().isReadAccessAllowed) {
            // 已经在ReadAction中，直接执行
            runnable()
        } else {
            // 不在ReadAction中，使用ReadAction包装
            ApplicationManager.getApplication().runReadAction(runnable)
        }
        
        return currentRange
    }

    override fun evaluate(express: String, xEvaluationCallback: XDebuggerEvaluator.XEvaluationCallback, xSourcePosition: XSourcePosition?) {
        var expr = express.trim()
        if (!expr.endsWith(')')) {
            val lastDot = express.lastIndexOf('.')
            val lastColon = express.lastIndexOf(':')
            if (lastColon > lastDot) // a:xx -> a.xx
                expr = expr.replaceRange(lastColon, lastColon + 1, ".")
        }
        eval(expr, xEvaluationCallback, xSourcePosition)
    }

    protected abstract fun eval(express: String, xEvaluationCallback: XDebuggerEvaluator.XEvaluationCallback, xSourcePosition: XSourcePosition?)
}

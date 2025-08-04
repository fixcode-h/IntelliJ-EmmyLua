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

package com.tang.intellij.lua.refactoring.extract

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.tang.intellij.lua.LuaBundle
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.refactoring.LuaRefactoringUtil

/**
 * Lua函数提取重构处理器
 * 支持将选中的代码块提取为独立的函数
 */
class LuaExtractMethodHandler : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            CommonRefactoringUtil.showErrorHint(
                project, editor, 
                LuaBundle.message("refactor.extract_method.validation.no_selection"), 
                LuaBundle.message("refactor.extract_method.title"), null
            )
            return
        }

        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        
        val startElement = file.findElementAt(startOffset)
        val endElement = file.findElementAt(endOffset - 1)
        
        if (startElement == null || endElement == null) {
            CommonRefactoringUtil.showErrorHint(
                project, editor,
                LuaBundle.message("refactor.extract_method.validation.cannot_determine_range"),
                LuaBundle.message("refactor.extract_method.title"), null
            )
            return
        }

        val extractOperation = LuaExtractMethodOperation(
            project, editor, file, startElement, endElement, startOffset, endOffset
        )
        
        if (!extractOperation.isValidSelection()) {
            CommonRefactoringUtil.showErrorHint(
                project, editor,
                LuaBundle.message("refactor.extract_method.validation.invalid_selection"),
                LuaBundle.message("refactor.extract_method.title"), null
            )
            return
        }

        extractOperation.performExtraction()
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        // 不支持从元素数组调用
    }
}

/**
 * 函数提取操作类
 */
class LuaExtractMethodOperation(
    private val project: Project,
    private val editor: Editor,
    private val file: PsiFile,
    private val startElement: PsiElement,
    private val endElement: PsiElement,
    private val startOffset: Int,
    private val endOffset: Int
) {
    
    private var extractedStatements: List<LuaStatement> = emptyList()
    private var inputVariables: Set<String> = emptySet()
    private var outputVariables: Set<String> = emptySet()
    private var functionName: String = "extractedFunction"

    /**
     * 检查选择是否有效
     */
    fun isValidSelection(): Boolean {
        val statements = findSelectedStatements()
        if (statements.isEmpty()) {
            return false
        }
        
        extractedStatements = statements
        analyzeVariables()
        return true
    }

    /**
     * 执行提取操作
     */
    fun performExtraction() {
        val dialog = LuaExtractMethodDialog(project, this)
        if (dialog.showAndGet()) {
            functionName = dialog.getFunctionName()
            doExtraction()
        }
    }

    /**
     * 获取输入变量列表
     */
    fun getInputVariables(): Set<String> = inputVariables

    /**
     * 获取输出变量列表  
     */
    fun getOutputVariables(): Set<String> = outputVariables

    /**
     * 获取建议的函数名
     */
    fun getSuggestedFunctionName(): String = functionName

    /**
     * 查找选中的语句
     */
    private fun findSelectedStatements(): List<LuaStatement> {
        val statements = mutableListOf<LuaStatement>()
        
        // 查找包含选择范围的最小公共父元素
        var commonParent = startElement
        while (commonParent.textRange.startOffset > startOffset || 
               commonParent.textRange.endOffset < endOffset) {
            commonParent = commonParent.parent ?: break
        }

        // 收集选择范围内的语句
        commonParent.children.forEach { child ->
            if (child is LuaStatement) {
                val childRange = child.textRange
                if (childRange.startOffset >= startOffset && childRange.endOffset <= endOffset) {
                    statements.add(child)
                }
            }
        }

        return statements
    }

    private fun extractStatements(): List<LuaStatement> {
        val statements = mutableListOf<LuaStatement>()
        
        // 查找选中范围内的语句
        file.children.forEach { child ->
            collectStatements(child, statements)
        }
        
        return statements.filter { statement ->
            val statementRange = statement.textRange
            statementRange.startOffset >= startOffset && statementRange.endOffset <= endOffset
        }
    }

    /**
     * 递归收集语句
     */
    private fun collectStatements(element: PsiElement, statements: MutableList<LuaStatement>) {
        if (element is LuaStatement) {
            statements.add(element)
        }
        
        element.children.forEach { child ->
            // 只收集在选中范围内的语句
            val childRange = child.textRange
            if (childRange != null && child is LuaStatement) {
                if (childRange.startOffset >= startOffset && childRange.endOffset <= endOffset) {
                    statements.add(child)
                }
            }
        }
    }

    /**
     * 分析变量使用情况
     */
    private fun analyzeVariables() {
        val definedVars = mutableSetOf<String>()
        val usedVars = mutableSetOf<String>()
        val externallyUsedVars = mutableSetOf<String>()

        // 分析提取代码块中定义和使用的变量
        extractedStatements.forEach { statement ->
            analyzeStatement(statement, definedVars, usedVars)
        }

        // 分析哪些变量在代码块外部被使用
        val remainingCode = getRemainingCodeAfterExtraction()
        remainingCode.forEach { statement ->
            analyzeStatementUsage(statement, definedVars, externallyUsedVars)
        }

        // 输入变量：在提取代码中使用但不在其中定义的变量
        inputVariables = usedVars - definedVars

        // 输出变量：在提取代码中定义且在外部使用的变量
        outputVariables = definedVars.intersect(externallyUsedVars)
    }

    /**
     * 分析单个语句中的变量定义和使用
     */
    private fun analyzeStatement(statement: LuaStatement, definedVars: MutableSet<String>, usedVars: MutableSet<String>) {
        when (statement) {
            is LuaLocalDef -> {
                // 局部变量定义
                statement.nameList?.nameDefList?.forEach { nameDef ->
                    definedVars.add(nameDef.name)
                }
                // 分析赋值表达式中使用的变量
                statement.exprList?.exprList?.forEach { expr ->
                    analyzeExpressionUsage(expr, usedVars)
                }
            }
            is LuaAssignStat -> {
                // 赋值语句
                statement.varExprList.exprList.forEach { expr ->
                    if (expr is LuaNameExpr) {
                        definedVars.add(expr.name)
                    }
                }
                statement.valueExprList?.exprList?.forEach { expr ->
                    analyzeExpressionUsage(expr, usedVars)
                }
            }
            else -> {
                // 其他语句，只分析变量使用
                analyzeElementUsage(statement, usedVars)
            }
        }
    }

    /**
     * 分析表达式中的变量使用
     */
    private fun analyzeExpressionUsage(expr: LuaExpr, usedVars: MutableSet<String>) {
        when (expr) {
            is LuaNameExpr -> {
                usedVars.add(expr.name)
            }
            else -> {
                analyzeElementUsage(expr, usedVars)
            }
        }
    }

    /**
     * 递归分析元素中的变量使用
     */
    private fun analyzeElementUsage(element: PsiElement, usedVars: MutableSet<String>) {
        element.children.forEach { child ->
            if (child is LuaNameExpr) {
                usedVars.add(child.name)
            } else {
                analyzeElementUsage(child, usedVars)
            }
        }
    }

    /**
     * 分析语句中变量的使用情况（用于检查外部使用）
     */
    private fun analyzeStatementUsage(statement: LuaStatement, definedVars: Set<String>, externallyUsedVars: MutableSet<String>) {
        analyzeElementUsage(statement) { varName ->
            if (varName in definedVars) {
                externallyUsedVars.add(varName)
            }
        }
    }

    /**
     * 递归分析元素使用情况的重载方法
     */
    private fun analyzeElementUsage(element: PsiElement, onVariableUsed: (String) -> Unit) {
        element.children.forEach { child ->
            if (child is LuaNameExpr) {
                onVariableUsed(child.name)
            } else {
                analyzeElementUsage(child, onVariableUsed)
            }
        }
    }

    /**
     * 获取提取后剩余的代码
     */
    private fun getRemainingCodeAfterExtraction(): List<LuaStatement> {
        val allStatements = mutableListOf<LuaStatement>()
        
        // 查找包含文件的所有语句
        file.children.forEach { child ->
            collectAllStatements(child, allStatements)
        }

        // 排除被提取的语句
        return allStatements - extractedStatements.toSet()
    }

    /**
     * 收集所有语句
     */
    private fun collectAllStatements(element: PsiElement, statements: MutableList<LuaStatement>) {
        if (element is LuaStatement) {
            statements.add(element)
        }
        element.children.forEach { child ->
            collectAllStatements(child, statements)
        }
    }

    /**
     * 执行实际的提取操作
     */
    private fun doExtraction() {
        val extractedCode = generateExtractedFunction()
        val callCode = generateFunctionCall()
        
        // 在写操作中执行文档修改
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            val document = editor.document
            
            // 替换选中的代码为函数调用
            document.replaceString(startOffset, endOffset, callCode)
            
            // 在适当位置插入提取的函数
            insertExtractedFunction(extractedCode)
        }
    }

    /**
     * 生成提取的函数代码
     */
    private fun generateExtractedFunction(): String {
        val params = inputVariables.joinToString(", ")
        val returns = if (outputVariables.isNotEmpty()) {
            "\n    return ${outputVariables.joinToString(", ")}"
        } else ""
        
        val body = extractedStatements.joinToString("\n") { statement -> "    ${statement.text}" }
        
        return """
function $functionName($params)
$body$returns
end
        """.trimIndent()
    }

    /**
     * 生成函数调用代码
     */
    private fun generateFunctionCall(): String {
        val args = inputVariables.joinToString(", ")
        return if (outputVariables.isNotEmpty()) {
            "${outputVariables.joinToString(", ")} = $functionName($args)"
        } else {
            "$functionName($args)"
        }
    }

    /**
     * 插入提取的函数到合适的位置
     */
    private fun insertExtractedFunction(functionCode: String) {
        // 简单实现：在文件开头插入
        // 注意：此方法应该在写操作中调用
        val document = editor.document
        document.insertString(0, "$functionCode\n\n")
    }
}
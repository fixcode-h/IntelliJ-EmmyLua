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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringActionHandler
import com.tang.intellij.lua.psi.*

/**
 * 提取方法处理器
 * Created by tangzx on 2017/4/18.
 */
class LuaExtractMethodHandler : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val selectionModel = editor.selectionModel
        if (selectionModel.hasSelection()) {
            val startOffset = selectionModel.selectionStart
            val endOffset = selectionModel.selectionEnd
            
            val operation = LuaExtractMethodOperation(project, editor, file, startOffset, endOffset)
            operation.perform()
        }
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        // 不支持从元素数组调用
    }
}

/**
 * 提取方法操作类
 */
class LuaExtractMethodOperation(
    private val project: Project,
    private val editor: Editor,
    private val file: PsiFile,
    private val startOffset: Int,
    private val endOffset: Int
) {
    private var functionName: String = "extractedFunction"
    private var extractedStatements: List<LuaStatement> = emptyList()
    private var inputVariables: List<String> = emptyList()
    private var outputVariables: List<String> = emptyList()
    
    // 新增：成员函数相关变量
    private var containingMethod: LuaClassMethodDef? = null
    private var containingFunction: LuaFuncDef? = null
    private var isInMemberFunction: Boolean = false
    private var memberFunctionType: String = ":" // ":" 或 "."

    fun perform() {
        // 分析选中的代码
        analyzeSelection()
        
        // 分析包含的函数
        analyzeContainingFunction()
        
        // 获取选中的元素
        val elements = getSelectedElements()
        if (elements.isEmpty()) return
        
        // 分析变量
        val variableAnalysis = analyzeVariables(elements)
        inputVariables = variableAnalysis.parameters
        outputVariables = variableAnalysis.returns
        
        // 显示对话框
        val dialog = LuaExtractMethodDialog(project, this)
        if (dialog.showAndGet()) {
            val newFunctionName = dialog.getFunctionName()
            performExtraction(newFunctionName)
        }
    }

    /**
     * 分析选中的代码
     */
    private fun analyzeSelection() {
        val startElement = file.findElementAt(startOffset)
        val endElement = file.findElementAt(endOffset - 1)
        
        if (startElement != null && endElement != null) {
            val commonParent = PsiTreeUtil.findCommonParent(startElement, endElement)
            
            // 查找包含的语句
            val statements = mutableListOf<LuaStatement>()
            var current = startElement
            
            while (current != null && current.textRange.startOffset < endOffset) {
                val stat = PsiTreeUtil.getParentOfType(current, LuaStatement::class.java)
                if (stat != null && !statements.contains(stat)) {
                    if (stat.textRange.startOffset >= startOffset && stat.textRange.endOffset <= endOffset) {
                        statements.add(stat)
                    }
                }
                current = current.nextSibling
            }
            
            extractedStatements = statements
        }
    }

    /**
     * 分析包含的函数
     */
    private fun analyzeContainingFunction() {
        containingMethod = PsiTreeUtil.getParentOfType(file.findElementAt(startOffset), LuaClassMethodDef::class.java)
        containingFunction = PsiTreeUtil.getParentOfType(file.findElementAt(startOffset), LuaFuncDef::class.java)
        
        if (containingMethod != null) {
            isInMemberFunction = true
            // 检测方法类型（: 或 .）
            val methodName = containingMethod!!.classMethodName
            memberFunctionType = if (methodName?.text?.contains(":") == true) ":" else "."
        } else {
            isInMemberFunction = false
            memberFunctionType = ""
        }
    }

    /**
     * 获取类名
     */
    private fun getClassName(): String {
        containingMethod?.let { method ->
            // 尝试从方法定义中获取类名
            val nameExpr = method.classMethodName?.expr
            if (nameExpr is LuaIndexExpr) {
                return nameExpr.prefixExpr?.text ?: "M"
            }
        }
        // 默认返回 M（常见的 Lua 模块名）
        return "M"
    }

    /**
     * 分析变量
     */
    private fun analyzeVariables(elements: List<PsiElement>): VariableAnalysis {
        val usedVariables = mutableSetOf<String>()
        val localVariables = mutableSetOf<String>()
        val returnVariables = mutableSetOf<String>()

        // 首先收集当前作用域内的所有local变量
        collectLocalVariablesInScope(localVariables)

        for (element in elements) {
            element.accept(object : LuaRecursiveVisitor() {
                override fun visitNameExpr(o: LuaNameExpr) {
                    super.visitNameExpr(o)
                    o.name?.let { name ->
                        // 只有local变量才考虑作为参数，全局变量不需要传入
                        if (localVariables.contains(name)) {
                            usedVariables.add(name)
                        }
                    }
                }

                override fun visitLocalDef(o: LuaLocalDef) {
                    super.visitLocalDef(o)
                    o.nameList?.nameDefList?.forEach { nameDef: LuaNameDef ->
                        nameDef.name?.let { name: String -> 
                            localVariables.add(name)
                        }
                    }
                }

                override fun visitAssignStat(o: LuaAssignStat) {
                    super.visitAssignStat(o)
                    o.varExprList.exprList.forEach { expr: LuaExpr ->
                        if (expr is LuaNameExpr) {
                            expr.name?.let { name: String -> 
                                // 只有已知的local变量才添加到定义变量中
                                if (localVariables.contains(name)) {
                                    // 这里不需要特殊处理，因为已经在local变量集合中
                                }
                            }
                        }
                    }
                }
            })
        }

        // 查找在选择范围后使用的变量（用于返回值分析）
        val definedVariables = mutableSetOf<String>()
        for (element in elements) {
            element.accept(object : LuaRecursiveVisitor() {
                override fun visitLocalDef(o: LuaLocalDef) {
                    super.visitLocalDef(o)
                    o.nameList?.nameDefList?.forEach { nameDef: LuaNameDef ->
                        nameDef.name?.let { name: String -> definedVariables.add(name) }
                    }
                }

                override fun visitAssignStat(o: LuaAssignStat) {
                    super.visitAssignStat(o)
                    o.varExprList.exprList.forEach { expr: LuaExpr ->
                        if (expr is LuaNameExpr) {
                            expr.name?.let { name: String -> definedVariables.add(name) }
                        }
                    }
                }
            })
        }

        val nextElement = elements.lastOrNull()?.nextSibling
        if (nextElement != null) {
            var current: PsiElement? = nextElement
            while (current != null) {
                if (current is LuaStatement) {
                    current.accept(object : LuaRecursiveVisitor() {
                        override fun visitNameExpr(o: LuaNameExpr) {
                            super.visitNameExpr(o)
                            o.name?.let { name ->
                                if (definedVariables.contains(name)) {
                                    returnVariables.add(name)
                                }
                            }
                        }
                    })
                    current = current.nextSibling
                } else {
                    current = current.nextSibling
                }
            }
        }

        // 过滤参数：排除在选中代码中定义的变量，以及成员函数中的self
        val parameters = usedVariables.filter { 
            !definedVariables.contains(it) && 
            !(isInMemberFunction && memberFunctionType == ":" && it == "self")
        }
        val returns = returnVariables.toList()

        return VariableAnalysis(parameters.toList(), returns)
    }

    /**
     * 收集当前作用域内的所有local变量
     */
    private fun collectLocalVariablesInScope(localVariables: MutableSet<String>) {
        // 从当前位置向上查找，收集所有可见的local变量
        var current = file.findElementAt(startOffset)
        
        while (current != null) {
            // 查找包含的函数或块
            val containingBlock = PsiTreeUtil.getParentOfType(current, LuaBlock::class.java)
            val containingFunc = PsiTreeUtil.getParentOfType(current, LuaFuncDef::class.java)
            val containingMethod = PsiTreeUtil.getParentOfType(current, LuaClassMethodDef::class.java)
            
            // 收集函数参数
            containingFunc?.funcBody?.paramNameDefList?.forEach { param ->
                param.name?.let { localVariables.add(it) }
            }
            
            containingMethod?.funcBody?.paramNameDefList?.forEach { param ->
                param.name?.let { localVariables.add(it) }
            }
            
            // 收集块内的local变量定义
            containingBlock?.let { block ->
                block.accept(object : LuaRecursiveVisitor() {
                    override fun visitLocalDef(o: LuaLocalDef) {
                        // 只收集在当前位置之前定义的local变量
                        if (o.textRange.startOffset < startOffset) {
                            o.nameList?.nameDefList?.forEach { nameDef ->
                                nameDef.name?.let { localVariables.add(it) }
                            }
                        }
                    }
                })
            }
            
            current = current.parent
        }
    }

    /**
     * 生成提取的函数代码
     */
    private fun generateExtractedFunction(functionName: String, parameters: List<String>, returns: List<String>, elements: List<PsiElement>): String {
        val paramStr = parameters.joinToString(", ")
        
        // 获取选中代码的文本，保持原有的缩进
        val selectedText = file.text.substring(startOffset, endOffset)
        val lines = selectedText.split("\n")
        
        // 计算基础缩进（移除最小的缩进）
        val minIndent = lines.filter { it.trim().isNotEmpty() }
            .map { line -> line.takeWhile { it.isWhitespace() }.length }
            .minOrNull() ?: 0
            
        val bodyCode = lines.joinToString("\n") { line ->
            if (line.trim().isEmpty()) {
                ""
            } else {
                "    " + line.drop(minIndent)
            }
        }.trim()
        
        val returnStr = if (returns.isNotEmpty()) {
            "\n    return ${returns.joinToString(", ")}"
        } else {
            ""
        }
        
        return if (isInMemberFunction) {
            // 生成成员函数
            val className = getClassName()
            "function $className:$functionName($paramStr)\n    $bodyCode$returnStr\nend"
        } else {
            // 生成普通函数
            "local function $functionName($paramStr)\n    $bodyCode$returnStr\nend"
        }
    }

    /**
     * 生成函数调用代码，保持正确的缩进
     */
    private fun generateFunctionCall(functionName: String, parameters: List<String>, returns: List<String>): String {
        val paramStr = parameters.joinToString(", ")
        
        // 获取原始选中代码的缩进
        val selectedText = file.text.substring(startOffset, endOffset)
        val firstLine = selectedText.split("\n").first()
        val indent = firstLine.takeWhile { it.isWhitespace() }
        
        val callStr = if (isInMemberFunction) {
            "self:$functionName($paramStr)"
        } else {
            "$functionName($paramStr)"
        }

        val result = if (returns.isNotEmpty()) {
            val returnStr = returns.joinToString(", ")
            "local $returnStr = $callStr"
        } else {
            callStr
        }
        
        return indent + result
    }

    /**
     * 查找包含函数的结束位置
     */
    private fun findContainingFunctionEndOffset(): Int {
        val containingFunc = containingMethod ?: containingFunction
        return if (containingFunc != null) {
            containingFunc.textRange.endOffset
        } else {
            // 如果不在函数内，插入到文件末尾
            file.textLength
        }
    }







    /**
     * 执行实际的提取操作
     */
    private fun doExtraction() {
        // 分析包含的函数
        analyzeContainingFunction()
        
        // 获取选中的元素
        val elements = getSelectedElements()
        if (elements.isEmpty()) return
        
        // 分析变量
        val variableAnalysis = analyzeVariables(elements)
        inputVariables = variableAnalysis.parameters
        outputVariables = variableAnalysis.returns
        
        // 在写操作中执行文档修改
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@runWriteAction
            
            // 生成提取的函数
            val extractedFunction = generateExtractedFunction(functionName, inputVariables, outputVariables, elements)
            
            // 生成函数调用
            val functionCall = generateFunctionCall(functionName, inputVariables, outputVariables)
            
            // 找到插入位置（包含函数的结束位置）
            val insertOffset = findContainingFunctionEndOffset()
            
            // 先插入提取的函数
            document.insertString(insertOffset, "\n\n$extractedFunction")
            
            // 再替换选中的代码为函数调用
            document.replaceString(startOffset, endOffset, functionCall)
            
            // 提交文档更改
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
    
    private fun getSelectedElements(): List<PsiElement> {
        val elements = mutableListOf<PsiElement>()
        var current = file.findElementAt(startOffset)
        
        while (current != null && current.textRange.startOffset < endOffset) {
            if (current.textRange.startOffset >= startOffset) {
                elements.add(current)
            }
            current = current.nextSibling ?: current.parent?.nextSibling
        }
        
        return elements
    }


    
    /**
     * 获取建议的函数名
     */
    fun getSuggestedFunctionName(): String {
        return functionName
    }
    
    /**
     * 获取输入变量列表
     */
    fun getInputVariables(): List<String> {
        return inputVariables
    }
    
    /**
     * 获取输出变量列表
     */
    fun getOutputVariables(): List<String> {
        return outputVariables
    }
    
    /**
     * 执行提取操作
     */
    fun performExtraction(newFunctionName: String) {
        functionName = newFunctionName
        doExtraction()
    }
}

/**
 * 变量分析结果
 */
data class VariableAnalysis(
    val parameters: List<String>,
    val returns: List<String>
)
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

package com.tang.intellij.lua.reference

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.tang.intellij.lua.comment.psi.LuaDocTagField
import com.tang.intellij.lua.psi.LuaIndexExpr
import com.tang.intellij.lua.psi.LuaNameExpr
import com.tang.intellij.lua.psi.search.LuaShortNamesManager
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.ITyClass

/**
 * 当 `_MOE.Models.UGCEditorRoomModel` 解析到的是:
 *
 *     ---@field UGCEditorRoomModel UGCEditorRoomModel
 *
 * 这种「字段名 == 字段类型名」的 @field 声明时，
 * 把 Go To Declaration 的目标从 `---@field` 行替换为对应的 `---@class UGCEditorRoomModel` 声明处，
 * 方便跨文件直接跳到类定义。
 *
 * 若原始 reference.resolve() 失败，则退化为"按字段名猜父类型 -> 字段类型如果是 class 就跳到 class"，
 * 作为对缺失 @field 索引场景的兜底。
 *
 * 另外还处理方法调用场景，如 `_MOE.Models.UGCEditorRoomModel:MultiPlayGameWithCheck()`，
 * 通过 prefix 的名字推断真实类型，再在该类里查找方法 / 成员。
 */
class LuaGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        sourceElement ?: return null

        val parent = sourceElement.parent
        val indexExpr = parent as? LuaIndexExpr
        val nameExpr = parent as? LuaNameExpr
        if (indexExpr == null && nameExpr == null) return null

        val expr: PsiElement = indexExpr ?: nameExpr!!
        val reference = (indexExpr?.reference) ?: (nameExpr?.reference)
        val resolvedRaw = reference?.resolve()

        val context = SearchContext.get(expr.project)

        // —— 主路径：resolve 到 LuaDocTagField，且字段类型是同名 class ——
        val asField = resolvedRaw as? LuaDocTagField
        if (asField != null) {
            val stub = asField.stub
            val fieldName = stub?.name ?: asField.name
            val fieldType = stub?.type ?: asField.ty?.getType()

            if (fieldName != null && fieldType is ITyClass && fieldType.className == fieldName) {
                val luaClass = LuaShortNamesManager.getInstance(expr.project)
                    .findClass(fieldType.className, context)
                val classPsi = luaClass as? PsiElement
                if (classPsi != null && classPsi !== asField) {
                    return arrayOf(classPsi)
                }
            }
        }

        // —— 兜底路径：resolve 失败，自己按 parentType 猜字段类型，类型是 class 就跳到 class ——
        if (indexExpr != null) {
            val fieldName = indexExpr.name
            if (fieldName != null) {
                val parentType = try {
                    indexExpr.guessParentType(context)
                } catch (t: Throwable) {
                    null
                }

                val shortNames = LuaShortNamesManager.getInstance(expr.project)
                var resolvedField: PsiElement? = null
                parentType?.eachTopClass(com.intellij.util.Processor { ty ->
                    val member = ty.findMember(fieldName, context)
                    if (member != null) {
                        resolvedField = member
                        return@Processor false
                    }
                    true
                })

                val field = resolvedField as? LuaDocTagField
                if (field != null) {
                    val stub = field.stub
                    val fn = stub?.name ?: field.name
                    val ft = stub?.type ?: field.ty?.getType()
                    if (fn != null && ft is ITyClass && ft.className == fn) {
                        val clz = shortNames.findClass(ft.className, context) as? PsiElement
                        if (clz != null) {
                            return arrayOf(clz)
                        }
                    }
                } else {
                    // 兜底 B：字段名 == 类名时直接按名跳
                    val clz = shortNames.findClass(fieldName, context) as? PsiElement
                    if (clz != null) {
                        return arrayOf(clz)
                    }
                }

                // —— 兜底 C：处理方法调用场景，如 `_MOE.Models.UGCEditorRoomModel:MultiPlayGameWithCheck()` ——
                // 外层 indexExpr 的 prefix 如果也是一个"字段名==类名"形式的 LuaIndexExpr / LuaNameExpr，
                // 把 prefix 的类型"修正"为那个同名 class，然后在该 class 里查 fieldName 对应的成员。
                val prefixClassName = inferPrefixClassName(indexExpr, context)
                if (prefixClassName != null) {
                    val method = shortNames.findMethod(prefixClassName, fieldName, context) as? PsiElement
                    if (method != null) {
                        return arrayOf(method)
                    }
                    val prefixClass = shortNames.findClass(prefixClassName, context)
                    val prefixTy = prefixClass?.type
                    val member = if (prefixTy != null) {
                        shortNames.findMember(prefixTy, fieldName, context) as? PsiElement
                    } else null
                    if (member != null) {
                        return arrayOf(member)
                    }
                }
            }
        }

        return null
    }

    /**
     * 沿 indexExpr 的 prefix 链推断"真实"类名。
     * 对形如 `_MOE.Models.UGCEditorRoomModel:MultiPlayGameWithCheck()` 的调用：
     *   - indexExpr 是 `...UGCEditorRoomModel:MultiPlayGameWithCheck`
     *   - prefix 是 `_MOE.Models.UGCEditorRoomModel`（LuaIndexExpr）
     *   - prefix.name == "UGCEditorRoomModel"，且同名 class 存在 → 返回 "UGCEditorRoomModel"
     * 若 prefix 的 guessType 已经能正常推出 class，优先用它。
     */
    private fun inferPrefixClassName(indexExpr: LuaIndexExpr, context: SearchContext): String? {
        val prefix = indexExpr.exprList.firstOrNull() ?: return null

        // 1) 优先：让 prefix 自己推类型
        val prefixTy = try { prefix.guessType(context) } catch (t: Throwable) { null }
        var found: String? = null
        prefixTy?.eachTopClass(com.intellij.util.Processor { ty ->
            if (ty is ITyClass) {
                found = ty.className
                return@Processor false
            }
            true
        })
        if (found != null) return found

        // 2) 按名: 如果 prefix 是 index/name expr 且 name 对应一个同名 class，返回该类名
        val shortNames = LuaShortNamesManager.getInstance(indexExpr.project)
        val prefixName: String? = when (prefix) {
            is LuaIndexExpr -> prefix.name
            is LuaNameExpr -> prefix.name
            else -> null
        }
        if (prefixName != null) {
            val cls = shortNames.findClass(prefixName, context)
            if (cls != null) return prefixName
        }
        return null
    }

    override fun getActionText(context: DataContext): String? = null
}

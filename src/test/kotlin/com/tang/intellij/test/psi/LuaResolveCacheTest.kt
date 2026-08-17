package com.tang.intellij.test.psi

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.psi.LuaNameExpr
import com.tang.intellij.lua.psi.resolveLocal
import com.tang.intellij.test.LuaTestBase

class LuaResolveCacheTest : LuaTestBase() {
    fun `test local resolve cache is invalidated by psi modification`() {
        val file = myFixture.configureByText(
            "resolve.lua",
            "local foo=1\n-- shadowed\nprint(foo)"
        )
        val reference = PsiTreeUtil.findChildrenOfType(file, LuaNameExpr::class.java).last { it.name == "foo" }
        val original = resolveLocal(reference)
        assertNotNull(original)

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(
                "local foo=1\n".length,
                "local foo=1\n-- shadowed".length,
                "local foo=2"
            )
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val updatedReference = PsiTreeUtil.findChildrenOfType(file, LuaNameExpr::class.java).last { it.name == "foo" }
        val updated = resolveLocal(updatedReference)
        assertNotNull(updated)
        assertNotSame(original, updated)
        assertTrue(updated!!.textOffset > original!!.textOffset)
    }
}

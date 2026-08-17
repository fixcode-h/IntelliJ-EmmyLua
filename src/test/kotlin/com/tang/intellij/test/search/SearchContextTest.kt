package com.tang.intellij.test.search

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.search.GlobalSearchScope
import com.tang.intellij.lua.search.GuardType
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.Ty
import com.tang.intellij.test.LuaTestBase

class SearchContextTest : LuaTestBase() {
    fun `test temporary state is restored after failure`() {
        val file = myFixture.configureByText("context.lua", "local value = 1")
        val context = SearchContext.get(project)
        val originalScope = context.scope

        assertThrows(IllegalStateException::class.java) {
            context.withIndex(3) {
                context.withScope(GlobalSearchScope.EMPTY_SCOPE) {
                    context.withRecursionGuard(file, GuardType.RecursionCall) {
                        throw IllegalStateException("failed inference")
                    }
                }
            }
        }

        assertEquals(-1, context.index)
        assertSame(originalScope, context.scope)
        assertSame(Ty.STRING, context.withRecursionGuard(file, GuardType.RecursionCall) { Ty.STRING })
    }

    fun `test cancellation escapes stub inference and restores thread context`() {
        val file = myFixture.configureByText("cancel.lua", "return 1")

        assertThrows(ProcessCanceledException::class.java) {
            SearchContext.withStub(project, file, Ty.UNKNOWN) {
                throw ProcessCanceledException()
            }
        }

        val current = SearchContext.get(project)
        assertFalse(current.forStub)
    }
}

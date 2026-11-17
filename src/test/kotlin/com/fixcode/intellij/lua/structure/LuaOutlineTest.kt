package com.fixcode.intellij.lua.structure

import com.fixcode.intellij.lua.structure.LuaOutline.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class LuaOutlineTest {
    /** 把大纲画成缩进文本，方便整体比较。 */
    private fun dump(text: String): String = buildString {
        fun walk(nodes: List<LuaOutline.Node>, indent: String) {
            for (n in nodes) {
                append(indent).append(n.kind).append(' ').append(n.name).append(n.params ?: "").append('\n')
                walk(n.children, "$indent  ")
            }
        }
        walk(LuaOutline.build(text), "")
    }

    @Test
    fun unluaClass() {
        val text = """
            ---@type WBP_Foo_C
            local M = UnLua.Class()

            local function helper(a, b)
                if a then return b end
                for i = 1, 3 do print(i) end
                while false do end
                repeat until true
                return a
            end

            M.Config = { Speed = 1, Name = "x", Sub = { Deep = true }, Fn = function(x) end }

            function M:Initialize(Initializer)
                self.Count = 0
                local function inner() end
            end

            function M.StaticFn() end

            function M:ReceiveTick(DeltaSeconds)
                local t = { a = 1 }
            end

            return M
        """.trimIndent()
        assertEquals(
            """
            VARIABLE M
              FIELD Config
                FIELD Speed
                FIELD Name
                FIELD Sub
                  FIELD Deep
                STATIC_METHOD Fn(x)
              METHOD Initialize(Initializer)
              STATIC_METHOD StaticFn()
              METHOD ReceiveTick(DeltaSeconds)
            FUNCTION helper(a, b)

            """.trimIndent().let { reorder(it) },
            reorder(dump(text)),
        )
    }

    /** 顶层顺序按源码：M 先于 helper。这里不重排，只是去掉末尾空行差异。 */
    private fun reorder(s: String) = s.trimEnd()

    @Test
    fun globalsAndOwnerGroups() {
        val text = """
            GlobalVar = 10
            function GlobalFunc(x, ...) end
            function Foo.Bar:Baz() end
            t[k].f = 1
            print(GlobalVar)
            local a <const>, b = 1, 2
            local Tbl = {
              key = 1,
              ["str"] = 2,
              [3] = 4,
              x = y == z,
            }
        """.trimIndent()
        assertEquals(
            """
            VARIABLE GlobalVar
            FUNCTION GlobalFunc(x, ...)
            CLASS Foo.Bar
              METHOD Baz()
            VARIABLE a
            VARIABLE b
            VARIABLE Tbl
              FIELD key
              FIELD x
            """.trimIndent(),
            dump(text).trimEnd(),
        )
    }

    @Test
    fun rangesCoverFunctionBody() {
        val text = "function M:Foo()\n  local x = 1\nend\nlocal y = 2"
        val m = LuaOutline.build(text).single { it.name == "M" }
        val foo = m.children.single()
        assertEquals(Kind.METHOD, foo.kind)
        assertEquals(0, foo.start)
        assertEquals(text.indexOf("end") + 3, foo.end)
        assertEquals(text.indexOf("Foo"), foo.offset)
    }

    @Test
    fun brokenCodeDoesNotThrow() {
        for (text in listOf("function", "function M:", "local", "local x =", "M.", "x = {", "function f( a", "local t = { a = ")) {
            LuaOutline.build(text)
        }
    }
}

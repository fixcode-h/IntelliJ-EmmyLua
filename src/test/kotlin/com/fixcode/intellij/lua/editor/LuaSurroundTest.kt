package com.fixcode.intellij.lua.editor

import com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Surround With：按选中的行包裹，不需要语法树。 */
class LuaSurroundTest : BasePlatformTestCase() {
    private fun surround(description: String, before: String, after: String) {
        myFixture.configureByText("a.lua", before)
        val surrounder: Surrounder = LuaSurroundDescriptor().surrounders.first { it.templateDescription == description }
        SurroundWithHandler.invoke(project, myFixture.editor, myFixture.file, surrounder)
        myFixture.checkResult(after)
    }

    fun `test if 包裹并选中条件`() = surround(
        "if ... then / end",
        "function f()\n    <selection>a()\n    b()</selection>\nend",
        "function f()\n    if <selection>true</selection> then\n        a()\n        b()\n    end\nend",
    )

    fun `test do 包裹整行选择`() = surround(
        "do / end",
        "<selection>a()\n</selection>b()\n",
        "do<caret>\n    a()\nend\nb()\n",
    )

    fun `test for 包裹`() = surround(
        "for i = 1, n do / end",
        "<selection>print(i)</selection>",
        "for i = 1, <selection>n</selection> do\n    print(i)\nend",
    )

    fun `test function 包裹`() = surround(
        "function() / end",
        "<selection>x = 1</selection>",
        "<caret>function()\n    x = 1\nend",
    )

    fun `test region 包裹且保留空行`() = surround(
        "--region / --endregion",
        "<selection>a()\n\nb()</selection>",
        "--region <selection>description</selection>\n    a()\n\n    b()\n--endregion",
    )

    fun `test 选区从行中间开始也按整行`() = surround(
        "do / end",
        "local x = <selection>1</selection>",
        "do<caret>\n    local x = 1\nend",
    )
}

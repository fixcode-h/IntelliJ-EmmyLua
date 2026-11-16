package com.fixcode.intellij.lua.debug.xdebugger

/**
 * 调用栈帧名的补救：从 C/C++ 调进来的 Lua 函数（UnLua 覆写的 ReceiveBeginPlay、委托回调等）
 * lua_getinfo 拿不到名字，调试器给的是「?」。这时看函数定义那一行（lineDefined）写的是什么。
 */
object LuaFrameName {
    private const val NAME = """[A-Za-z_]\w*(?:\s*[.:]\s*[A-Za-z_]\w*)*"""
    private val DECLARED = Regex("""\bfunction\s+($NAME)\s*\(""")
    private val ASSIGNED = Regex("""($NAME)\s*=\s*function\s*\(""")

    /** 定义行里写的函数名（`function M:Foo(`、`local function foo(`、`M.foo = function(`），匿名函数返回 null。 */
    fun fromDefinition(line: String): String? {
        val code = line.substringBefore("--")
        val m = DECLARED.find(code) ?: ASSIGNED.find(code) ?: return null
        return m.groupValues[1].replace(Regex("""\s+"""), "")
    }

    /** 帧名：调试器给了就用；给的是「?」时按定义行推断，推断不出就标成匿名函数。 */
    fun display(name: String, lineDefined: Int?, lineText: (Int) -> String?): String {
        if (name != "?" || lineDefined == null || lineDefined <= 0) return name
        return lineText(lineDefined)?.let { fromDefinition(it) } ?: "匿名函数（第 $lineDefined 行）"
    }
}

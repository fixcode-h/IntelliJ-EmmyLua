package com.fixcode.intellij.lua.format

import com.fixcode.intellij.lua.project.LuaFormatterConfig
import junit.framework.TestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * 用真的 luafmt 跑一遍「补提示 → 格式化」：只在环境变量 `EMMYLUA_LUAFMT` 指向可执行文件时运行。
 * 升级 emmylua_ls（luafmt 同一个 Release）时跑一次，看表字段还对不对齐、提示有没有留在结果里。
 */
class LuaFormatterRealTest : TestCase() {
    private val exe = System.getenv("EMMYLUA_LUAFMT")?.takeIf { it.isNotBlank() }

    private fun format(src: String): String {
        val dir = Files.createTempDirectory("luafmt-test")
        val config = dir.resolve("default.toml").also { Files.writeString(it, LuaFormatterConfig.TEMPLATE) }
        val p = ProcessBuilder(exe, "--stdin", "--stdin-filename", dir.resolve("a.lua").toString(), "--spaces", "4", "--config", config.toString()).start()
        p.outputStream.use { it.write(LuaAlignHints.apply(src).toByteArray(StandardCharsets.UTF_8)) }
        val out = p.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        assertEquals(0, p.waitFor())
        return LuaFormatFixups.apply(out)
    }

    fun `test 表字段的等号对齐`() {
        if (exe == null) return
        val src = "local TestConfig = {\n    FakeMin = 1,\n    FakePauseMin = 0.2,\n    StunLoseThreshold = 3,\n    MaxWine = 100\n}\n"
        val want = "local TestConfig = {\n    FakeMin           = 1,\n    FakePauseMin      = 0.2,\n    StunLoseThreshold = 3,\n    MaxWine           = 100\n}\n"
        assertEquals(want, format(src))
    }

    fun `test 长调用和长拼接不折行，多行的表保持多行`() {
        if (exe == null) return
        val long = "_SP.Logger.LogWarn(\"[ClinkGlasses] s_BW_CG_ClinkGlassesSystem=>EnterIntro  已有进行中的玩法，拒绝进入 NPCInstanceID=\" .. tostring(NPCInstanceID))\n"
        assertEquals(long, format(long))
        val table = "local t = {\n    a = 1,\n    b = 2\n}\n"
        assertEquals(table, format(table))
    }

    fun `test 匿名函数后的尾参数和右括号在同一行`() {
        if (exe == null) return
        val src = "foo(a, function()\n    x()\nend, b)\n"
        assertEquals("foo(a, function ()\n    x()\nend, b)\n", format(src))
    }

    fun `test 单行表和只有一个字段的不留多余空格`() {
        if (exe == null) return
        val src = "local a = { x = 1, y = 2 }\nlocal b = {\n    only = 1\n}\n"
        val out = format(src)
        assertFalse(out, out.contains("  ="))
    }
}

package com.fixcode.intellij.lua.project

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnrealDeclProgressTest {
    private fun status(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `native 和 assets 阶段按 done total 算进度`() {
        val p = UnrealDeclProgress.of(status("""{"phase":"native","done":30,"total":120}"""))!!
        assertEquals("生成 C++ 反射类型的声明  30 / 120", p.text)
        assertEquals(0.25, p.fraction!!, 1e-9)
        val a = UnrealDeclProgress.of(status("""{"phase":"assets","done":5,"total":10}"""))!!
        assertEquals(0.5, a.fraction!!, 1e-9)
    }

    @Test
    fun `没有总数时不定进度`() {
        assertNull(UnrealDeclProgress.of(status("""{"phase":"native"}"""))!!.fraction)
    }

    @Test
    fun `空闲但还有蓝图没加载完算加载阶段`() {
        val p = UnrealDeclProgress.of(status("""{"phase":"idle","loadDone":2,"loadTotal":8}"""))!!
        assertEquals("加载没打开过的蓝图，补全成员  2 / 8", p.text)
        assertNull(UnrealDeclProgress.of(status("""{"phase":"idle","loadDone":8,"loadTotal":8}""")))
        assertNull(UnrealDeclProgress.of(status("""{"phase":"idle"}""")))
    }

    @Test
    fun `完成摘要`() {
        assertEquals(
            "UE 反射声明已更新：共 900 个声明文件，这次写了 3 个、删了 1 个，用时 2.5 秒，4 个蓝图只有类名（没加载过，可用「补全全部蓝图」）",
            UnrealDeclProgress.summary(status("""{"files":900,"written":3,"deleted":1,"lastPassMs":2500,"stubs":4}""")),
        )
        assertEquals("UE 反射声明已更新：没有变化", UnrealDeclProgress.summary(status("""{"written":0}""")))
    }
}

package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.code.CodeLocation
import com.fixcode.intellij.lua.debugger.mcp.code.DocSymbol
import com.fixcode.intellij.lua.debugger.mcp.code.FuzzyMatcher
import com.fixcode.intellij.lua.debugger.mcp.code.LuaCodeBackend
import com.fixcode.intellij.lua.debugger.mcp.code.LuaCodeTools
import com.fixcode.intellij.lua.debugger.mcp.code.LuaFile
import com.fixcode.intellij.lua.debugger.mcp.code.LuaSymbols
import com.fixcode.intellij.lua.debugger.mcp.code.ServerState
import com.fixcode.intellij.lua.debugger.mcp.code.WorkspaceSymbolHit
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpCallLog
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpToolDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LuaCodeToolsTest {
    private lateinit var backend: FakeCodeBackend
    private var backends: List<LuaCodeBackend> = emptyList()
    private lateinit var client: McpTestClient
    private val T = LuaCodeTools.NAME

    // 等索引的时长缩到毫秒级，单测不真等一分钟
    private val FAST = LuaCodeTools.Timeouts(indexWaitMillis = 400, indexPollMillis = 20, stallPolls = 3)

    @Before
    fun setUp() {
        backend = FakeCodeBackend()
        backend.add("Content/Script/UI/MainPanel.lua", FakeCodeBackend.MAIN_PANEL, FakeCodeBackend.mainPanelSymbols())
        backend.add("Content/Script/Weapon/Gun.lua", FakeCodeBackend.GUN, FakeCodeBackend.gunSymbols())
        backend.add("Plugins/UnLua/Content/Script/UnLua.lua", "UnLua = {}\nfunction UnLua.Class() end\n",
            listOf(com.fixcode.intellij.lua.debugger.mcp.code.DocSymbol("UnLua.Class", 12, "()", 1, 9)), library = true)
        backends = listOf(backend)
        client = McpTestClient(McpToolDispatcher(LuaCodeTools({ backends }, FAST).all(), McpCallLog()))
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(o: McpTestClient.ToolOutcome) = o["items"] as List<Map<String, Any?>>

    @Test
    fun `说明三段齐全，参数 snake_case，只读`() {
        val t = client.tools().single()
        assertEquals("lua_code", t["name"])
        val desc = t["description"] as String
        assertTrue(desc, "USE FIRST WHEN:" in desc && "INSTEAD OF:" in desc && "NOT FOR:" in desc)
        assertTrue("说明过长：${desc.length}", desc.length <= 1200)
        val titles = (t["_meta"] as Map<*, *>)["titles"] as Map<*, *>
        assertNotNull(titles["zh"]); assertNotNull(titles["en"])
        assertEquals(true, (t["annotations"] as Map<*, *>)["readOnlyHint"])
        @Suppress("UNCHECKED_CAST")
        val schema = t["inputSchema"] as Map<String, Any?>
        assertEquals(false, schema["additionalProperties"])
        assertEquals(listOf("action"), schema["required"])
        (schema["properties"] as Map<*, *>).keys.forEach { assertTrue("参数应为 snake_case：$it", (it as String).matches(Regex("[a-z][a-z0-9_]*"))) }
    }

    @Test
    fun `未知参数和缺 action 报错`() {
        val r = client.call(T, mapOf("action" to "search", "qeury" to "x"))
        assertEquals("invalid_argument", r.code)
        assertTrue(r["message"] as String, "query" in (r["message"] as String))
        assertEquals("invalid_argument", client.call(T, mapOf("query" to "x")).code)
        assertEquals("invalid_argument", client.call(T, mapOf("action" to "search", "query" to "x", "kinds" to listOf("func"))).code)
    }

    @Test
    fun `search 方法按全名拆容器，列号指向名字，带签名和模块`() {
        val r = client.call(T, mapOf("action" to "search", "query" to "OnButtonClicked"))
        assertFalse(r.raw, r.isError)
        val first = items(r)[0]
        assertEquals("OnButtonClicked", first["name"])
        assertEquals("method", first["kind"])
        assertEquals("M", first["container"])
        assertEquals(7L, first["line"])
        assertEquals(12L, first["col"]) // "function M:OnButtonClicked" 里 O 在第 12 列
        assertEquals("(btn, idx)", first["sig"])
        assertEquals("UI.MainPanel", first["module"])
        assertTrue((first["file"] as String).endsWith("UI/MainPanel.lua"))
        assertNull(first["local"])
        assertEquals(1L, r["count"]); assertEquals(false, r["hasMore"])
    }

    @Test
    fun `search 模糊：CamelHumps、前缀、限定容器和模块`() {
        assertEquals("OnButtonClicked", items(client.call(T, mapOf("action" to "search", "query" to "OBC")))[0]["name"])
        assertEquals("OnButtonClicked", items(client.call(T, mapOf("action" to "search", "query" to "onbut")))[0]["name"])
        val fire = items(client.call(T, mapOf("action" to "search", "query" to "Gun:Fire")))
        assertEquals(listOf("Fire"), fire.map { it["name"] })
        // UnLua 的 local M ... return M：按模块名找到 M.Show
        val show = items(client.call(T, mapOf("action" to "search", "query" to "UI.MainPanel.Show")))
        assertEquals("Show", show.single()["name"])
        assertEquals("function", show.single()["kind"])
        assertTrue(items(client.call(T, mapOf("action" to "search", "query" to "Weapon.Gun.Show"))).isEmpty())
    }

    @Test
    fun `search 注解里的类和字段、表构造里的字段、local 标记`() {
        val cls = items(client.call(T, mapOf("action" to "search", "query" to "MainPanel", "kinds" to listOf("class"))))
        assertEquals("MainPanel", cls[0]["name"]); assertEquals(1L, cls[0]["line"]); assertEquals(": UserWidget", cls[0]["sig"])
        val title = items(client.call(T, mapOf("action" to "search", "query" to "Title")))[0]
        assertEquals("field", title["kind"]); assertEquals("MainPanel", title["container"]); assertEquals("string", title["sig"])
        val damage = items(client.call(T, mapOf("action" to "search", "query" to "damage")))[0]
        assertEquals("GunConfig", damage["container"]); assertEquals("field", damage["kind"])
        val helper = items(client.call(T, mapOf("action" to "search", "query" to "helper", "match" to "exact")))
        assertEquals(true, helper.single()["local"]); assertEquals("1", helper.single()["value"])
        val noLocals = items(client.call(T, mapOf("action" to "search", "query" to "helper", "include_locals" to false)))
        assertEquals(listOf("GlobalHelper"), noLocals.map { it["name"] })
    }

    @Test
    fun `search 默认只查项目，scope=all 带库，workspace symbol 补全局`() {
        // 库目录不逐个文件取符号（UE 声明动辄上万个文件），只靠 workspace/symbol
        assertTrue(items(client.call(T, mapOf("action" to "search", "query" to "Class", "match" to "exact", "scope" to "all"))).isEmpty())
        assertEquals(2, backend.docSymbolCalls.get())
        backend.workspace += WorkspaceSymbolHit("AActor", 5, null, "/work/Decl/AActor.lua", 0, 10)
        assertTrue(items(client.call(T, mapOf("action" to "search", "query" to "AActor"))).isEmpty())
        val actor = items(client.call(T, mapOf("action" to "search", "query" to "AActor", "scope" to "all"))).single()
        assertEquals("class", actor["kind"]); assertEquals(true, actor["lib"]); assertEquals(1L, actor["line"])
    }

    @Test
    fun `search 结果按文件版本缓存，文件变了才重新取`() {
        client.call(T, mapOf("action" to "search", "query" to "Fire"))
        val after1 = backend.docSymbolCalls.get()
        assertEquals(2, after1) // 两个项目文件，库不扫
        client.call(T, mapOf("action" to "search", "query" to "Show"))
        assertEquals(after1, backend.docSymbolCalls.get())
        backend.add("Content/Script/Weapon/Gun.lua", FakeCodeBackend.GUN, FakeCodeBackend.gunSymbols(), stamp = 2)
        client.call(T, mapOf("action" to "search", "query" to "Show"))
        assertEquals(after1 + 1, backend.docSymbolCalls.get())
    }

    @Test
    fun `分页信封`() {
        for (i in 0 until 40) {
            backend.add("Content/Script/Gen/F$i.lua", "function M.Handler$i() end\n",
                listOf(com.fixcode.intellij.lua.debugger.mcp.code.DocSymbol("M.Handler$i", 12, "()", 0, 9)))
        }
        val r = client.call(T, mapOf("action" to "search", "query" to "Handler", "limit" to 10))
        assertEquals(10L, r["count"]); assertEquals(40L, r["total"]); assertEquals(true, r["hasMore"]); assertEquals(10L, r["nextOffset"])
        val last = client.call(T, mapOf("action" to "search", "query" to "Handler", "limit" to "10", "offset" to 30))
        assertEquals(10L, last["count"]); assertEquals(false, last["hasMore"]); assertNull(last["nextOffset"])
        assertEquals("invalid_argument", client.call(T, mapOf("action" to "search", "query" to "Handler", "limit" to 500)).code)
    }

    @Test
    fun `服务器没准备好时报 indexing 或 engine_unavailable，files 不需要服务器`() {
        backend.state = ServerState.Starting
        assertEquals("indexing", client.call(T, mapOf("action" to "search", "query" to "Fire")).code)
        backend.state = ServerState.Unavailable("找不到 emmylua_ls。")
        val r = client.call(T, mapOf("action" to "outline", "path" to "UI.MainPanel"))
        assertEquals("engine_unavailable", r.code); assertEquals("找不到 emmylua_ls。", r["message"])
        // search 不报错，整份按文本查
        val text = client.call(T, mapOf("action" to "search", "query" to "Fire"))
        assertFalse(text.raw, text.isError)
        assertTrue(text.raw, items(text).any { it["name"] == "Fire" })
        assertTrue(text.raw, "找不到 emmylua_ls" in (text["note"] as String))
        assertFalse(client.call(T, mapOf("action" to "files", "query" to "Gun")).isError)
        backends = emptyList()
        assertEquals("engine_unavailable", client.call(T, mapOf("action" to "files", "query" to "Gun")).code)
    }

    @Test
    fun `所有文件等到上限都还没索引时先按文本查并说明，部分一直不给结果时没命中才说明`() {
        backend.entries.values.forEach { it.symbols = null }
        val early = client.call(T, mapOf("action" to "search", "query" to "Fire"))
        assertFalse(early.raw, early.isError)
        assertTrue(early.raw, items(early).any { it["name"] == "Fire" })
        assertTrue(early.raw, "按文本" in (early["note"] as String))
        backend.add("Content/Script/Weapon/Gun.lua", FakeCodeBackend.GUN, FakeCodeBackend.gunSymbols())
        val r = client.call(T, mapOf("action" to "search", "query" to "Fire"))
        assertFalse(r.raw, r.isError)
        assertNull(r.raw, r["note"])
        val miss = client.call(T, mapOf("action" to "search", "query" to "NoSuchThing"))
        assertTrue(miss.raw, "不给结果" in (miss["note"] as String))
    }

    @Test
    fun `还在建索引时等它建完再返回，不让 Agent 拿着不全的结果`() {
        val gun = backend.entries.values.first { it.file.path.endsWith("Gun.lua") }
        val symbols = gun.symbols
        gun.symbols = null
        Thread { Thread.sleep(60); gun.symbols = symbols }.start()
        val r = client.call(T, mapOf("action" to "search", "query" to "Fire"))
        assertFalse(r.raw, r.isError)
        assertNull(r.raw, r["note"])
        assertTrue(r.raw, items(r).any { it["name"] == "Fire" || (it["name"] as String).endsWith("Fire") })
    }

    @Test
    fun `一直不给结果的文件认定不收：不再等、不再每次都提，没命中时才说一句`() {
        backend.add("Content/Script/Excluded/Old.lua", "function Old() end\n", null)
        val first = client.call(T, mapOf("action" to "search", "query" to "Fire"))
        assertFalse(first.raw, first.isError)
        assertNull(first.raw, first["note"])
        val calls = backend.docSymbolCalls.get()
        val miss = client.call(T, mapOf("action" to "search", "query" to "NoSuchThing"))
        assertTrue(miss.raw, "不给结果" in (miss["note"] as String) && "Excluded" in (miss["note"] as String))
        assertEquals("认定不收的文件不再问服务器", calls, backend.docSymbolCalls.get())
        // 不收的文件按文本认声明，照样搜得到
        val old = client.call(T, mapOf("action" to "search", "query" to "Old", "match" to "exact"))
        assertEquals(old.raw, "Content/Script/Excluded/Old.lua", (items(old).single()["file"] as String).substringAfter("/work/Game/"))
    }

    @Test
    fun `emmylua_ls 不列的表外赋值按文本补上；outline 在服务器不给结果时按文本列`() {
        val text = "local M = {}\nM.Inner = {}\nM.Inner.ApplyRule = function(a, b) end\nfunction M.Inner:Apply2() end\nM.Count = 3\nreturn M\n"
        backend.add("Content/Script/Deco/Rule.lua", text, listOf(DocSymbol("M", 13, null, 0, 6)))
        val r = client.call(T, mapOf("action" to "search", "query" to "ApplyRule", "match" to "exact"))
        val hit = items(r).single()
        assertEquals("Inner", (hit["container"] as String).substringAfterLast('.'))
        assertEquals(3L, hit["line"])
        backend.add("Content/Script/Deco/Big.lua", "function Big.Run(x) end\n", null)
        val o = client.call(T, mapOf("action" to "outline", "path" to "Deco.Big"))
        assertFalse(o.raw, o.isError)
        assertEquals("Run", items(o).single()["name"])
        assertTrue(o.raw, "按文本" in (o["note"] as String))
    }

    @Test
    fun `files 按模块路径、斜杠路径和文件名片段`() {
        fun names(q: String, scope: String? = null) = items(client.call(T, buildMap {
            put("action", "files"); put("query", q); if (scope != null) put("scope", scope)
        })).map { it["module"] }
        assertEquals("UI.MainPanel", names("UI.MainPanel")[0])
        assertEquals("UI.MainPanel", names("UI/MainPanel.lua")[0])
        assertEquals("UI.MainPanel", names("MainPan")[0])
        assertEquals("UnLua", names("UnLua")[0])
        assertTrue(names("UnLua", "project").isEmpty())
        val one = items(client.call(T, mapOf("action" to "files", "query" to "Gun")))[0]
        assertEquals("Gun.lua", one["name"]); assertEquals("/work/Game/Content/Script/Weapon/Gun.lua", one["path"])
    }

    @Test
    fun `outline 按源码顺序，接受模块名和相对路径`() {
        val r = client.call(T, mapOf("action" to "outline", "path" to "UI.MainPanel"))
        assertFalse(r.raw, r.isError)
        assertEquals("/work/Game/Content/Script/UI/MainPanel.lua", r["file"])
        assertEquals("UI.MainPanel", r["module"])
        val its = items(r)
        assertEquals(listOf("MainPanel", "Title", "M", "helper", "OnButtonClicked", "Show", "localFn", "GlobalHelper"), its.map { it["name"] })
        assertEquals(its.map { (it["line"] as Number).toInt() }.sorted(), its.map { (it["line"] as Number).toInt() })
        assertNull(its[0]["file"])
        val rel = client.call(T, mapOf("action" to "outline", "path" to "Content/Script/Weapon/Gun", "kinds" to "function"))
        assertEquals(listOf("Fire"), items(rel).map { it["name"] })
        val abs = client.call(T, mapOf("action" to "outline", "path" to "/work/Game/Content/Script/Weapon/Gun.lua"))
        assertFalse(abs.isError)
        assertEquals("target_not_found", client.call(T, mapOf("action" to "outline", "path" to "UI.Nope")).code)
    }

    @Test
    fun `references 按名字找到声明再查，带每行内容`() {
        val gun = "/work/Game/Content/Script/Weapon/Gun.lua"
        val main = "/work/Game/Content/Script/UI/MainPanel.lua"
        backend.references = listOf(CodeLocation(main, 15, 9), CodeLocation(gun, 2, 4))
        val r = client.call(T, mapOf("action" to "references", "query" to "GlobalHelper"))
        assertFalse(r.raw, r.isError)
        assertEquals(Triple(main, 15, 9), backend.lastReference)
        @Suppress("UNCHECKED_CAST")
        val target = r["target"] as Map<String, Any?>
        assertEquals("GlobalHelper", target["name"]); assertEquals(16L, target["line"])
        val its = items(r)
        // 按文件路径排序：UI/ 在 Weapon/ 前面
        assertEquals(main, its[0]["file"])
        assertEquals(gun, its[1]["file"]); assertEquals(3L, its[1]["line"]); assertEquals(5L, its[1]["col"])
        assertEquals("GlobalHelper(1, 2)", its[1]["text"])
    }

    @Test
    fun `references 用 file+line+query 定位，参数缺了报错`() {
        client.call(T, mapOf("action" to "references", "file" to "Weapon/Gun.lua", "line" to 2, "query" to "Fire"))
        // 相对脚本根的路径；Fire 在第 2 行第 14 列（0 起 13）
        assertEquals(Triple("/work/Game/Content/Script/Weapon/Gun.lua", 1, 13), backend.lastReference)
        client.call(T, mapOf("action" to "references", "file" to "Weapon.Gun", "line" to 2, "col" to 10))
        assertEquals(Triple("/work/Game/Content/Script/Weapon/Gun.lua", 1, 9), backend.lastReference)
        assertEquals("invalid_argument", client.call(T, mapOf("action" to "references")).code)
        assertEquals("invalid_argument", client.call(T, mapOf("action" to "references", "file" to "Weapon.Gun")).code)
        assertEquals("invalid_argument", client.call(T, mapOf("action" to "references", "file" to "Weapon.Gun", "line" to 2, "query" to "Nope")).code)
        assertEquals("target_not_found", client.call(T, mapOf("action" to "references", "query" to "NoSuchThing")).code)
    }

    @Test
    fun `多个项目时按 project 或路径选，结果带项目名`() {
        val other = FakeCodeBackend("Tools", "/work/Tools")
        other.add("Scripts/Build.lua", "function Build() end\n", listOf(com.fixcode.intellij.lua.debugger.mcp.code.DocSymbol("Build", 12, "()", 0, 9)))
        backends = listOf(backend, other)
        val r = client.call(T, mapOf("action" to "search", "query" to "Build", "project" to "tools"))
        assertEquals("Tools", r["project"]); assertEquals("Build", items(r).single()["name"])
        val o = client.call(T, mapOf("action" to "outline", "path" to "/work/Tools/Scripts/Build.lua"))
        assertEquals("Tools", o["project"])
        assertEquals("invalid_argument", client.call(T, mapOf("action" to "search", "query" to "x", "project" to "Nope")).code)
    }

    @Test
    fun `接受 IDE 工具通用的 projectPath：按路径选项目，上级目录也算，对不上且多个项目时报错说改用 project`() {
        val other = FakeCodeBackend("Tools", "/srv/Tools")
        other.add("Scripts/Build.lua", "function Build() end\n", listOf(com.fixcode.intellij.lua.debugger.mcp.code.DocSymbol("Build", 12, "()", 0, 9)))
        backends = listOf(backend, other)
        // 项目里面的路径（用户 2026-11-21 报的就是传了脚本根）
        assertEquals("Tools", client.call(T, mapOf("action" to "search", "query" to "Build", "projectPath" to "/srv/Tools/Scripts"))["project"])
        // 项目根的上级目录（工作区根）
        assertEquals("Game", client.call(T, mapOf("action" to "search", "query" to "Fire", "projectPath" to "/work"))["project"])
        val bad = client.call(T, mapOf("action" to "search", "query" to "Build", "projectPath" to "/elsewhere"))
        assertEquals("invalid_argument", bad.code)
        assertTrue(bad.raw, "改用 project" in bad.raw)
        // 项目嵌套时取根最深的那个，不按列表顺序
        val inner = FakeCodeBackend("Inner", "/work/Game/Plugins/Inner")
        inner.add("Scripts/Build.lua", "function Build() end\n", listOf(com.fixcode.intellij.lua.debugger.mcp.code.DocSymbol("Build", 12, "()", 0, 9)))
        backends = listOf(backend, inner)
        assertEquals("Inner", client.call(T, mapOf("action" to "search", "query" to "Build", "projectPath" to "/work/Game/Plugins/Inner/Scripts"))["project"])
        // 上级目录同时盖住多个项目：算对不上
        assertEquals("invalid_argument", client.call(T, mapOf("action" to "search", "query" to "Build", "projectPath" to "/work")).code)
        // 只开了一个项目时对不上也照查
        backends = listOf(other)
        assertEquals("Build", items(client.call(T, mapOf("action" to "search", "query" to "Build", "projectPath" to "/elsewhere"))).single()["name"])
        // schema 里不出现 projectPath（参数名保持 snake_case）
        assertFalse(((client.tools().single()["inputSchema"] as Map<*, *>)["properties"] as Map<*, *>).containsKey("projectPath"))
    }

    // ------------------------------------------------------------------ 纯逻辑

    @Test
    fun `模糊匹配排序：完全相同 大于 前缀 大于 CamelHumps 大于 子串`() {
        val m = FuzzyMatcher("OnBut")
        assertNotNull(m.match("OnButtonClicked"))
        assertNull(m.match("Close"))
        val exact = FuzzyMatcher("Fire").match("Fire")!!
        val prefix = FuzzyMatcher("Fire").match("FireWeapon")!!
        val camel = FuzzyMatcher("FW").match("FireWeapon")!!
        val sub = FuzzyMatcher("eWea").match("FireWeapon")!!
        assertTrue(exact > prefix && prefix > camel && camel > sub)
        assertNotNull(FuzzyMatcher("gAC").match("GetActorComponent"))
        assertNull(FuzzyMatcher("OBC").match("OnClick"))
        assertNotNull(FuzzyMatcher("On*Click").match("OnButtonClicked"))
    }

    @Test
    fun `模块名：Content-Script 之后，或相对最长的根`() {
        assertEquals("UI.MainPanel", LuaSymbols.moduleName("C:\\Game\\Content\\Script\\UI\\MainPanel.lua", emptyList()))
        assertEquals("lib.util", LuaSymbols.moduleName("/p/src/lib/util.lua", listOf("/p", "/p/src")))
        assertNull(LuaSymbols.moduleName("/p/a.b/c.lua", listOf("/p")))
        assertNull(LuaSymbols.moduleName("/elsewhere/c.lua", listOf("/p")))
        assertEquals("C" to "Fire", LuaSymbols.splitQuery("C:Fire"))
        assertEquals("UI.MainPanel" to "Show", LuaSymbols.splitQuery("UI.MainPanel.Show"))
        assertEquals(null to "Show", LuaSymbols.splitQuery(".Show"))
    }

    @Test
    fun `resolveFile 不碰文件系统地处理 点点 和 Windows 路径`() {
        val f = LuaFile("C:\\Game\\Content\\Script\\UI\\MainPanel.lua")
        val known = listOf(f to "UI.MainPanel")
        assertEquals(f, LuaSymbols.resolveFile("C:/Game/Content/Script/UI/MainPanel.lua", emptyList(), known) { null })
        assertEquals(f, LuaSymbols.resolveFile("Content/Script/Other/../UI/MainPanel", listOf("C:\\Game"), known) { null })
        assertEquals(f, LuaSymbols.resolveFile("ui.mainpanel", listOf("C:\\Game"), known) { null })
        assertNull(LuaSymbols.resolveFile("D:/x.lua", emptyList(), known) { null })
    }
}

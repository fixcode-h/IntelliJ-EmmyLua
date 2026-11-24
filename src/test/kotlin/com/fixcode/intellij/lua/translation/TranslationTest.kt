package com.fixcode.intellij.lua.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TranslationTextTest {
    @Test
    fun `段落合并、列表和标签单独成段、代码行不翻译`() {
        val units = TranslationText.split(
            """
            Returns the actor that owns this
            component, or nil when detached.

            @param Index the slot to read
            - first item of the list
            local x = Foo(1)
            """.trimIndent(),
            "zh-CN",
        )
        assertEquals(4, units.size)
        assertEquals("Returns the actor that owns this component, or nil when detached.", units[0].text)
        assertTrue(units[0].translate)
        assertTrue(units[1].gapBefore)
        assertEquals("@param Index the slot to read", units[1].text)
        assertEquals("- first item of the list", units[2].text)
        assertFalse(units[3].translate)
    }

    @Test
    fun `已经是目标语言的不翻译`() {
        assertFalse(TranslationText.needsTranslation("返回拥有这个组件的 Actor", "zh-CN"))
        assertTrue(TranslationText.needsTranslation("Returns the owner", "zh-CN"))
        assertFalse(TranslationText.needsTranslation("Returns the owner", "en"))
        assertTrue(TranslationText.needsTranslation("返回拥有者", "en"))
        assertFalse(TranslationText.needsTranslation("Foo", "zh-CN"))
    }

    @Test
    fun `目标语言解析和各服务的写法`() {
        assertEquals("zh-CN", TranslationText.resolveTarget("auto", englishUi = false))
        assertEquals("en", TranslationText.resolveTarget("auto", englishUi = true))
        assertEquals("ja", TranslationText.resolveTarget("JA", englishUi = false))
        assertEquals("zh-Hans", TranslationText.providerCode(TranslationProviderKind.MICROSOFT, "zh-CN"))
        assertEquals("zh-Hant", TranslationText.providerCode(TranslationProviderKind.MICROSOFT, "zh-TW"))
        assertEquals("zh-CN", TranslationText.providerCode(TranslationProviderKind.GOOGLE, "zh-CN"))
    }

    @Test
    fun `长段按句切块，中文拼接不加空格`() {
        val text = "First sentence here. Second sentence follows. Third one ends it."
        val chunks = TranslationText.chunk(text, 30)
        assertTrue(chunks.all { it.length <= 30 })
        assertEquals(text, chunks.joinToString(" "))
        assertEquals("第一句。第二句", TranslationText.joinChunks(listOf("第一句。", "第二句")))
        assertEquals("one two", TranslationText.joinChunks(listOf("one", " two ")))
    }

    @Test
    fun `去掉 Lua 和其他语言的注释符号`() {
        assertEquals(
            "Returns the owner\nof this component.\n@param x the value",
            TranslationText.cleanComment("    --- Returns the owner\n    --- of this component.\n    ---@param x the value"),
        )
        assertEquals("Block comment text", TranslationText.cleanComment("--[[ Block comment text ]]"))
        assertEquals("Doc line", TranslationText.cleanComment("/// Doc line"))
        assertEquals("a\nb", TranslationText.cleanComment("/*\n * a\n * b\n */"))
        assertNull(TranslationText.cleanComment("  --  "))
    }
}

class TranslationProvidersTest {
    @Test
    fun `谷歌回包和地址`() {
        assertEquals("你好世界", GoogleTranslateProvider.parse("""[[["你好","Hello",null],["世界","world",null]],null,"en"]"""))
        assertEquals("https://mirror.example.com", GoogleTranslateProvider.normalizeBaseUrl("mirror.example.com/"))
        assertTrue(GoogleTranslateProvider.requestUrl(null, "a b", "zh-CN").startsWith("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=a%20b"))
    }

    @Test
    fun `微软回包和错误`() {
        assertEquals("你好", MicrosoftTranslateProvider.parse("""[{"translations":[{"text":"你好","to":"zh-Hans"}]}]"""))
        assertEquals("""[{"Text":"hi \"x\""}]""", MicrosoftTranslateProvider.requestBody("hi \"x\""))
        val e = runCatching { MicrosoftTranslateProvider.parse("""{"error":{"code":401,"message":"bad token"}}""") }.exceptionOrNull()
        assertEquals("bad token", e?.message)
    }

    @Test
    fun `MyMemory 回包解实体、非 200 报原因`() {
        assertEquals("它是'好'的", MyMemoryProvider.parse("""{"responseData":{"translatedText":"它是&#39;好&#39;的"},"responseStatus":200}"""))
        val e = runCatching { MyMemoryProvider.parse("""{"responseData":{"translatedText":""},"responseStatus":"403","responseDetails":"QUOTA"}""") }.exceptionOrNull()
        assertEquals("QUOTA", e?.message)
        assertTrue(MyMemoryProvider.requestUrl("hi", "zh-CN").endsWith("&langpair=en%7Czh-CN"))
        assertTrue(MyMemoryProvider.requestUrl("你好", "en").endsWith("&langpair=zh-CN%7Cen"))
    }

    @Test
    fun `微软令牌缓存、401 后重取`() {
        var authCalls = 0
        var status = 200
        val http = object : TranslationHttp {
            override fun get(url: String, headers: Map<String, String>, timeoutMillis: Int): HttpResult {
                authCalls++
                return HttpResult(200, "token$authCalls")
            }

            override fun post(url: String, body: String, contentType: String, headers: Map<String, String>, timeoutMillis: Int) =
                HttpResult(status, """[{"translations":[{"text":"${headers["Authorization"]}"}]}]""")
        }
        val p = MicrosoftTranslateProvider()
        assertEquals("Bearer token1", p.translate(http, "a", "zh-CN", 1000))
        assertEquals("Bearer token1", p.translate(http, "b", "zh-CN", 1000))
        status = 401
        runCatching { p.translate(http, "c", "zh-CN", 1000) }
        status = 200
        assertEquals("Bearer token2", p.translate(http, "d", "zh-CN", 1000))
    }
}

class TranslationCacheTest {
    @Test
    fun `写文件、重新读、LRU 和清空`() {
        val dir = Files.createTempDirectory("emmy-tr")
        val file = dir.resolve("cache.jsonl")
        val c1 = TranslationCache(file, capacity = 16)
        for (i in 0 until 20) c1.put("k$i", "v$i")
        assertNull(c1.get("k0"))
        assertEquals("v19", c1.get("k19"))
        assertEquals(16, c1.count)

        val c2 = TranslationCache(file, capacity = 16)
        assertEquals("v19", c2.get("k19"))
        assertNull(c2.get("k3"))
        c2.clear()
        assertFalse(Files.exists(file))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `界面线程上不读文件`() {
        val dir = Files.createTempDirectory("emmy-tr")
        val file = dir.resolve("cache.jsonl")
        Files.writeString(file, TranslationCache.line("a", "b") + "\n")
        val queued = ArrayList<Runnable>()
        val c = TranslationCache(file, background = Executor { queued += it })
        assertNull(c.get("a", allowLoad = false))
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals("b", c.get("a", allowLoad = false))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `键和行格式和 UEToVS 一致`() {
        assertEquals("Google|zh-CN|Hello", TranslationCache.key(TranslationProviderKind.GOOGLE, "zh-CN", "Hello"))
        assertEquals("""{"k":"a","v":"b"}""", TranslationCache.line("a", "b"))
        assertEquals("a" to "b", TranslationCache.parse("""{"k":"a","v":"b"}"""))
        assertNull(TranslationCache.parse("not json"))
    }
}

class TranslationServiceTest {
    private class FakeProvider(var fail: Boolean = false) : TranslationProvider {
        val calls = AtomicInteger()
        override val kind = TranslationProviderKind.GOOGLE
        override val maxChunkChars = 1000
        override fun translate(http: TranslationHttp, text: String, target: String, timeoutMillis: Int): String {
            calls.incrementAndGet()
            if (fail) throw TranslationException("HTTP 503")
            return "译:$text"
        }
    }

    private val noHttp = object : TranslationHttp {
        override fun get(url: String, headers: Map<String, String>, timeoutMillis: Int) = HttpResult(500, "")
        override fun post(url: String, body: String, contentType: String, headers: Map<String, String>, timeoutMillis: Int) = HttpResult(500, "")
    }

    @Test
    fun `先查缓存、相同内容只请求一次`() {
        val provider = FakeProvider()
        val queued = ArrayList<Runnable>()
        val service = TranslationService({ TranslationOptions() }, TranslationCache(null), mapOf(provider.kind to provider), noHttp, Executor { queued += it })
        val a = service.translate("Hello world")
        val b = service.translate("Hello world")
        assertTrue(a === b)
        queued.forEach { it.run() }
        assertEquals("译:Hello world", a.get(1, TimeUnit.SECONDS).text)
        assertEquals("译:Hello world", service.cached("Hello world"))
        val c = Executor { it.run() }.let {
            TranslationService({ TranslationOptions() }, service.cache, mapOf(provider.kind to provider), noHttp, it)
        }.translate("Hello world").get(1, TimeUnit.SECONDS)
        assertTrue(c.fromCache)
        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `失败后冷却 30 秒内不再请求`() {
        val provider = FakeProvider(fail = true)
        var now = 1000L
        val service = TranslationService({ TranslationOptions() }, TranslationCache(null), mapOf(provider.kind to provider), noHttp, Executor { it.run() }, now = { now })
        assertEquals("HTTP 503", service.translate("Hello world").get().error)
        provider.fail = false
        assertEquals("HTTP 503", service.translate("Another sentence").get().error)
        assertEquals(1, provider.calls.get())
        now += TranslationService.COOLDOWN_MS + 1
        assertTrue(service.translate("Another sentence").get().ok)
        provider.fail = true
        service.translate("Third sentence here").get()
        service.resetCooldown()
        provider.fail = false
        assertTrue(service.translate("Third sentence here").get().ok)
    }
}

class HoverMarkdownTest {
    private val hover = """
        ```lua
        function M:GetOwner(index)
        ```

        ---

        Returns the actor that owns this
        component.

        @*param* `index` — the slot to read

        返回中文说明
    """.trimIndent()

    @Test
    fun `代码块和分隔线原样保留、段落切开`() {
        val md = HoverMarkdown.parse(hover, "zh-CN")
        assertEquals(listOf("Returns the actor that owns this component.", "@*param* `index` — the slot to read"), md.units.map { it.text })
        // 不翻译的时候原样拼回
        assertEquals(hover, md.render(TranslationDisplay.BILINGUAL) { null }.markdown)
    }

    @Test
    fun `双语在原文后面插占位，只显示译文替换原文`() {
        val md = HoverMarkdown.parse(hover, "zh-CN")
        val bi = md.render(TranslationDisplay.BILINGUAL) { "译" + it.text.length }
        assertEquals(2, bi.slots.size)
        assertTrue(bi.markdown.contains("component.\n\n${HoverMarkdown.slot(0)}\n"))
        val only = md.render(TranslationDisplay.TRANSLATION_ONLY) { if (it.text.startsWith("Returns")) "译文" else null }
        assertFalse(only.markdown.contains("Returns the actor"))
        assertTrue(only.markdown.contains("the slot to read"))
        assertEquals(listOf("译文"), only.slots)
    }

    @Test
    fun `HTML 里换占位、转义译文`() {
        val html = "<p>a</p>\n<p>${HoverMarkdown.slot(0)}</p>\n<p>${HoverMarkdown.slot(1)}</p>"
        val out = HoverMarkdown.fillSlots(html, listOf("x < `y`", "z")) { "[" + HoverMarkdown.inlineHtml(it) + "]" }
        assertEquals("<p>a</p>\n[x &lt; <code>y</code>]\n[z]", out)
        assertEquals("", HoverMarkdown.statusHtml(0, null))
        assertTrue(HoverMarkdown.statusHtml(0, "HTTP 503").contains("翻译失败：HTTP 503"))
        assertTrue(HoverMarkdown.statusHtml(2, null).contains("翻译中"))
    }
}

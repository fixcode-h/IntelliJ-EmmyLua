package com.fixcode.intellij.lua.project

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import junit.framework.TestCase

/** 新建 .luafmt.toml 写的模板只用 schema 里有的键和取值：emmylua_formatter 对拼错的键不报错，悄悄忽略。 */
class LuaFormatterConfigTest : TestCase() {
    private val schema: JsonObject by lazy {
        val text = javaClass.getResourceAsStream("/schemas/luafmt.schema.json")!!.reader().readText()
        JsonParser.parseString(text).asJsonObject
    }

    fun `test 模板的键和取值都在 schema 里`() {
        var section: JsonObject? = null
        var sectionName = ""
        var keys = 0
        for (raw in LuaFormatterConfig.TEMPLATE.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                sectionName = line.trim('[', ']')
                section = schema.getAsJsonObject("properties").getAsJsonObject(sectionName)
                assertNotNull("schema 里没有 [$sectionName]", section)
                continue
            }
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            val prop = section!!.getAsJsonObject("properties").getAsJsonObject(key)
            assertNotNull("schema 里没有 $sectionName.$key", prop)
            when (prop.get("type").asString) {
                "boolean" -> assertTrue("$sectionName.$key = $value", value == "true" || value == "false")
                "integer" -> assertNotNull("$sectionName.$key = $value", value.toIntOrNull())
                "string" -> {
                    val allowed = prop.getAsJsonArray("enum").map { it.asString }
                    assertTrue("$sectionName.$key = $value 不在 $allowed 里", value.trim('"') in allowed)
                }
            }
            keys++
        }
        assertTrue(keys > 10)
    }

    fun `test schema 拒绝未知字段`() {
        assertFalse(schema.get("additionalProperties").asBoolean)
        for ((name, section) in schema.getAsJsonObject("properties").entrySet()) {
            assertFalse(name, section.asJsonObject.get("additionalProperties").asBoolean)
        }
    }
}

package com.fixcode.intellij.lua.json

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService

/** `.emmyrc.json` / `.luarc.json` 挂上了 emmylua_ls 的 schema，能补全字段。 */
class EmmyrcSchemaTest : BasePlatformTestCase() {
    fun `test emmyrc 映射到 schema`() {
        val file = myFixture.configureByText(".emmyrc.json", "{}")
        val schema = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file.virtualFile)
        assertTrue(schema.any { it.name == "emmyrc.schema.json" })
    }

    fun `test emmyrc 字段补全`() {
        myFixture.configureByText(".emmyrc.json", "{\"runtime\": {\"<caret>\"}}")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertTrue(items.toString(), "version" in items)
    }

    fun `test luarc 也映射`() {
        myFixture.configureByText(".luarc.json", "{\"<caret>\"}")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertTrue(items.toString(), "workspace" in items && "diagnostics" in items)
    }

    fun `test 别的 json 不映射`() {
        val file = myFixture.configureByText("package.json", "{}")
        val schema = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file.virtualFile)
        assertFalse(schema.any { it.name == "emmyrc.schema.json" })
    }
}

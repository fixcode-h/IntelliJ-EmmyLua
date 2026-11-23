package com.fixcode.intellij.lua.json

import com.fixcode.intellij.lua.project.LuaFormatterConfig
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion

/**
 * `.emmyrc.json` / `.luarc.json` 的补全和校验。schema 拷自 emmylua-analyzer-rust
 * （`crates/emmylua_code_analysis/resources/schema.json`，MIT），版本跟 `gradle.properties` 的 `emmyLuaLsVersion` 走，
 * 升级服务器时一起换。emmylua_ls 读 `.luarc.json` 用的是同一套字段（另外认 `"runtime.version"` 这种点号写法，
 * schema 根上没禁止多余字段，所以不会误报）。
 */
class EmmyrcSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(EmmyrcSchemaProvider, LuafmtSchemaProvider)
}

object EmmyrcSchemaProvider : JsonSchemaFileProvider {
    val FILE_NAMES = setOf(".emmyrc.json", ".luarc.json")
    const val RESOURCE = "/schemas/emmyrc.schema.json"

    override fun isAvailable(file: VirtualFile): Boolean = file.name in FILE_NAMES
    override fun getName(): String = "EmmyLua (.emmyrc.json)"
    override fun getSchemaFile(): VirtualFile? = JsonSchemaProviderFactory.getResourceFile(EmmyrcSchemaProvider::class.java, RESOURCE)
    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_2020_12
}

/**
 * `.luafmt.toml` / `luafmt.toml`（格式化配置）的补全和校验。emmylua-analyzer-rust 没有发布这份 schema，
 * 是照 0.25.1 的 `crates/emmylua_formatter/src/config/mod.rs` 手写的，升级 emmylua_ls 时对一遍字段。
 * TOML 文件走 JSON Schema 靠 IDE 自带的 TOML 插件；没装 TOML 插件时只是没有补全。
 */
object LuafmtSchemaProvider : JsonSchemaFileProvider {
    const val RESOURCE = "/schemas/luafmt.schema.json"

    override fun isAvailable(file: VirtualFile): Boolean = file.name in LuaFormatterConfig.FILE_NAMES
    override fun getName(): String = "EmmyLua (.luafmt.toml)"
    override fun getSchemaFile(): VirtualFile? = JsonSchemaProviderFactory.getResourceFile(LuafmtSchemaProvider::class.java, RESOURCE)
    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_7
}

package com.fixcode.intellij.lua.project

import com.fixcode.intellij.lua.lsp.EmmyLuaWorkspaceConfig
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * 代码格式化（Ctrl+Alt+L）的配置文件。格式化由 emmylua_ls 内置的 emmylua_formatter 做，
 * 它从被格式化的文件往上找 `.luafmt.toml` / `luafmt.toml`，不读 `.emmyrc.json` 和 `.editorconfig`；
 * 缩进宽度、Tab / 空格、文末换行由 IDE 随请求发过去，会盖掉文件里的 `indent`。
 * 插件不下发格式化配置，只帮用户在项目根建这个文件；项目里没有时，整个文件的格式化用 [TEMPLATE]
 * （format/LuaFormattingService）。
 */
object LuaFormatterConfig {
    val FILE_NAMES = listOf(".luafmt.toml", "luafmt.toml")

    fun find(project: Project): Path? {
        val root = EmmyLuaWorkspaceConfig.projectRoot(project) ?: return null
        return FILE_NAMES.map { root.resolve(it) }.firstOrNull { Files.isRegularFile(it) }
    }

    /** 和格式化器一样从 [file] 所在目录往上找配置文件，找到最近的一个。 */
    fun findFor(file: Path): Path? {
        var dir: Path? = file.toAbsolutePath().parent
        while (dir != null) {
            FILE_NAMES.map { dir!!.resolve(it) }.firstOrNull { Files.isRegularFile(it) }?.let { return it }
            dir = dir.parent
        }
        return null
    }

    /** 有就返回现有的，没有就在项目根按 [TEMPLATE] 新建。 */
    fun findOrCreate(project: Project): Path? {
        find(project)?.let { return it }
        val root = EmmyLuaWorkspaceConfig.projectRoot(project) ?: return null
        return root.resolve(FILE_NAMES.first()).also { Files.writeString(it, TEMPLATE) }
    }

    /**
     * 新建时写的内容：尽量接近旧版「代码样式 → Lua」的默认效果（连续 local 对齐 =、多行表保持多行、
     * 行尾注释对齐、删多余的分号）。只写和格式化器默认值不同或旧版用户关心的项，其余看 schema 补全。
     * 用 emmylua_ls 0.25.1 实测过；升级 emmylua_ls 时用 LuaFormatterConfigTest 核对键名。
     */
    val TEMPLATE = """
        # EmmyLua 代码格式化（Ctrl+Alt+L）配置，由 emmylua_ls 内置的格式化器读取。
        # 这个文件对它所在目录及子目录里的 .lua 生效；改完下次格式化就用新规则，不用重启。
        # 缩进宽度、用 Tab 还是空格由 IDE 决定：设置 → 编辑器 → 代码样式 → 常规，或 .editorconfig 的 indent_style / indent_size。
        # 全部选项：https://github.com/EmmyLuaLs/emmylua-analyzer-rust/blob/main/docs/emmylua_formatter/options_CN.md

        [layout]
        max_line_width = 120
        max_blank_lines = 1
        # 源码里本来是多行的表、调用参数保持多行（不开时一行放得下就合成一行）
        prefer_table_layout_from_source = true
        prefer_call_args_layout_from_source = true
        # 表每个字段一行：改成 "Always"
        table_expand = "Auto"

        [output]
        # 保留语句末尾的分号：改成 true
        preserve_statement_semicolon = false
        trailing_comma = "Never"
        quote_style = "Preserve"

        [spacing]
        space_before_call_paren = false
        space_inside_braces = true
        space_inside_parens = false
        space_around_math_operator = true
        space_around_concat_operator = true
        space_around_assign_operator = true

        [comments]
        align_line_comments = true
        # 连续语句的行尾注释对齐；注释被推得太靠右就改成 false
        align_in_statements = true
        line_comment_min_spaces_before = 1

        [align]
        # 连续赋值语句的 = 对齐
        continuous_assign_statement = true
        table_field = true
    """.trimIndent() + "\n"
}

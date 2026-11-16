package com.fixcode.intellij.lua.project

import com.fixcode.intellij.lua.lsp.EmmyLuaServer
import com.fixcode.intellij.lua.lsp.EmmyLuaServerLocator
import com.fixcode.intellij.lua.lsp.EmmyLuaWorkspaceConfig
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import java.nio.file.Files

/** 设置 → 语言和框架 → EmmyLua。改了会影响 emmylua_ls 的项，应用后重启服务器。 */
class LuaSettingsConfigurable(private val project: Project) :
    BoundSearchableConfigurable("EmmyLua", "preferences.Lua", "preferences.Lua") {

    private val app = LuaSettings.instance
    private val proj = LuaProjectSettings.getInstance(project)

    private var serverPath = app.languageServerPath
    private var level = app.languageLevel
    private var detectUnreal = proj.detectUnrealProject
    private var libraries = proj.additionalSourcesRoot.joinToString("\n")

    override fun createPanel(): DialogPanel = panel {
        group("语言服务器（emmylua_ls）") {
            row("可执行文件：") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("选择 emmylua_ls"))
                    .bindText(::serverPath)
                    .align(AlignX.FILL)
                    .comment("留空用插件自带的。当前：${describeServer()}")
            }
            row("默认 Lua 版本：") {
                comboBox(LuaLanguageLevel.entries.filter { it != LuaLanguageLevel.LUA50 })
                    .bindItem({ level }, { level = it ?: LuaLanguageLevel.LUA54 })
                    .comment("工作区有 .emmyrc.json / .luarc.json 时以文件里的 runtime.version 为准")
            }
            row {
                button(if (EmmyLuaWorkspaceConfig.findUserConfig(project) != null) "打开 .emmyrc.json" else "新建 .emmyrc.json") {
                    openOrCreateEmmyrc()
                }.comment("诊断规则、require 路径、全局变量、格式化等都在这个文件里配，emmylua_ls 改完自动重载")
            }
        }
        group("库目录") {
            row {
                checkBox("自动探测 UE 项目，加入 UnLua 生成的 IntelliSense 声明")
                    .bindSelected(::detectUnreal)
                    .comment(describeUnreal())
            }
            row {
                label("额外库目录（每行一个，可写相对项目目录的路径）：")
            }
            row {
                textArea().rows(4).bindText(::libraries).align(AlignX.FILL)
                    .comment("库里的文件只做索引、跳转和补全，不报诊断")
            }
        }
        group("新建 Lua 文件") {
            row {
                checkBox("使用自定义模板").bindSelected(app::enableCustomFileTemplate)
            }
            row {
                textArea().rows(8).bindText(app::customFileTemplate).align(AlignX.FILL)
                    .comment("可用 \${USER} \${DATE} \${TIME} \${PROJECT_NAME} 和下面的文件名占位符")
            }
            row {
                checkBox("替换文件名占位符：").bindSelected(app::enableFileNameReplacement)
                textField().bindText(app::fileNamePlaceholder)
            }
        }
    }

    override fun apply() {
        super.apply()
        val newLibraries = libraries.lines().map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        val changed = serverPath != app.languageServerPath || level != app.languageLevel ||
            detectUnreal != proj.detectUnrealProject || !newLibraries.contentEquals(proj.additionalSourcesRoot)
        app.languageServerPath = serverPath.trim()
        app.languageLevel = level
        proj.detectUnrealProject = detectUnreal
        proj.additionalSourcesRoot = newLibraries
        if (changed) EmmyLuaServer.restart(project)
    }

    override fun isModified(): Boolean =
        super.isModified() || serverPath != app.languageServerPath || level != app.languageLevel ||
            detectUnreal != proj.detectUnrealProject || libraries != proj.additionalSourcesRoot.joinToString("\n")

    override fun reset() {
        serverPath = app.languageServerPath
        level = app.languageLevel
        detectUnreal = proj.detectUnrealProject
        libraries = proj.additionalSourcesRoot.joinToString("\n")
        super.reset()
    }

    private fun describeServer(): String {
        val located = EmmyLuaServerLocator.locate() ?: return "没找到"
        val source = when (located.source) {
            EmmyLuaServerLocator.Source.SETTINGS -> "设置"
            EmmyLuaServerLocator.Source.BUNDLED -> "插件自带"
            EmmyLuaServerLocator.Source.PATH -> "PATH"
        }
        return "${located.path}（$source）"
    }

    private fun describeUnreal(): String {
        val root = EmmyLuaWorkspaceConfig.projectRoot(project) ?: return ""
        val ue = UnrealIntelliSenseRoots.findUnrealProjectRoot(root) ?: return "没找到 .uproject"
        val dirs = UnrealIntelliSenseRoots.find(root)
        return if (dirs.isEmpty()) {
            "UE 项目：$ue；还没有 IntelliSense 目录，在 UE 编辑器里用 UnLua 的 Generate IntelliSense 生成"
        } else {
            "UE 项目：$ue<br>" + dirs.joinToString("<br>")
        }
    }

    private fun openOrCreateEmmyrc() {
        val root = EmmyLuaWorkspaceConfig.projectRoot(project) ?: return
        val file = EmmyLuaWorkspaceConfig.findUserConfig(project) ?: root.resolve(".emmyrc.json").also {
            Files.writeString(it, defaultEmmyrc(level.emmyrcName))
        }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private companion object {
        fun defaultEmmyrc(version: String) = """
            {
              "${'$'}schema": "https://raw.githubusercontent.com/EmmyLuaLs/emmylua-analyzer-rust/refs/heads/main/crates/emmylua_code_analysis/resources/schema.json",
              "runtime": {
                "version": "$version"
              },
              "workspace": {
                "library": []
              },
              "diagnostics": {
                "disable": []
              }
            }
        """.trimIndent() + "\n"
    }
}

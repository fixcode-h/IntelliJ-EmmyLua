package com.fixcode.intellij.lua.project

import com.fixcode.intellij.lua.lsp.EmmyLuaServer
import com.fixcode.intellij.lua.lsp.EmmyLuaServerLocator
import com.fixcode.intellij.lua.lsp.EmmyLuaWorkspaceConfig
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
import java.io.IOException
import java.nio.file.Files
import javax.swing.JCheckBox
import javax.swing.JLabel

/** 设置 → 语言和框架 → EmmyLua。改了会影响 emmylua_ls 的项，应用后重启服务器。 */
class LuaSettingsConfigurable(private val project: Project) :
    BoundSearchableConfigurable("EmmyLua", "preferences.Lua", "preferences.Lua") {

    private val app = LuaSettings.instance
    private val proj = LuaProjectSettings.getInstance(project)

    private var serverPath = app.languageServerPath
    private var level = app.languageLevel
    private var detectUnreal = proj.detectUnrealProject
    private var libraries = proj.additionalSourcesRoot.joinToString("\n")
    private var declStatus: JLabel? = null
    private var declCheck: JCheckBox? = null
    private var unrealLuaDecl = proj.unrealLuaDecl

    override fun createPanel(): DialogPanel = panel {
        row {
            link("调试相关的设置（MCP、热重载、真机、变量显示）在「工具 → EmmyLua 调试」") {
                SettingsJump.open(it.source as? java.awt.Component, SettingsJump.DEBUG_ID, com.fixcode.intellij.lua.debug.ui.LuaDebugBundle.message("settings.title"))
            }
        }
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
                checkBox("自动探测 UE 项目，加入 UE 反射声明（UELink 生成的或 UnLua 的 IntelliSense）")
                    .bindSelected(::detectUnreal)
                    .comment(describeUnreal())
            }
            row {
                declCheck = checkBox("让 UE 编辑器后台生成反射声明（需要 UEKit 的 UELink 2.5+）")
                    .bindSelected(::unrealLuaDecl)
                    .comment(
                        "默认不生成。勾上后 UE 编辑器在后台把 C++ 类和蓝图生成 Lua 声明到 Intermediate/UELink/LuaDecl，" +
                            "蓝图编译、资源保存、Live Coding 后只更新变了的文件；IDE 有这个目录时优先用它。UE 编辑器后开的也会自动打开"
                    ).component
            }
            row("主动刷新：") {
                for (mode in UnrealDecl.Refresh.entries) {
                    button(mode.label) { refreshUnrealDecl(mode) }
                }
                declStatus = label("").component
            }.comment("也在 工具 菜单里。「补全全部蓝图」在 UE 后台逐个加载没打开过的蓝图；「全部重写」不看清单重写所有文件")
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
        if (unrealLuaDecl != proj.unrealLuaDecl) UnrealDecl.setEnabledFromIde(project, unrealLuaDecl)
    }

    override fun isModified(): Boolean =
        super.isModified() || serverPath != app.languageServerPath || level != app.languageLevel ||
            detectUnreal != proj.detectUnrealProject || libraries != proj.additionalSourcesRoot.joinToString("\n") ||
            unrealLuaDecl != proj.unrealLuaDecl

    override fun reset() {
        serverPath = app.languageServerPath
        level = app.languageLevel
        detectUnreal = proj.detectUnrealProject
        libraries = proj.additionalSourcesRoot.joinToString("\n")
        unrealLuaDecl = proj.unrealLuaDecl
        super.reset()
    }

    private fun describeServer(): String = EmmyLuaServerLocator.describe() ?: "没找到"

    private fun describeUnreal(): String {
        val root = EmmyLuaWorkspaceConfig.projectRoot(project) ?: return ""
        val ue = UnrealIntelliSenseRoots.findUnrealProjectRoot(root) ?: return "没找到 .uproject"
        val dirs = UnrealIntelliSenseRoots.find(root)
        return if (dirs.isEmpty()) {
            "UE 项目：$ue；还没有声明目录：勾选下面的「让 UE 编辑器后台生成反射声明」，或在 UE 编辑器里用 UnLua 的 Generate IntelliSense"
        } else {
            "UE 项目：$ue<br>" + dirs.joinToString("<br>")
        }
    }

    private fun refreshUnrealDecl(mode: UnrealDecl.Refresh) {
        val checked = declCheck?.isSelected
        declStatus?.text = "正在连 UE 编辑器…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                UnrealDecl.refresh(project, mode, checked).also { UnrealDecl.watchInBackground(project, it) }
            } catch (e: IOException) {
                e.message ?: "失败"
            }
            ApplicationManager.getApplication().invokeLater({ declStatus?.text = text }, ModalityState.any())
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

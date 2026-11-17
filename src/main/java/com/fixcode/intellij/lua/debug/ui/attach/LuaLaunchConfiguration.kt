package com.fixcode.intellij.lua.debug.ui.attach

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.util.ProgramParametersUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.session.LuaDebugTargetConfiguration
import com.fixcode.intellij.lua.debug.ui.session.NoProcessState
import com.fixcode.intellij.lua.debug.xdebugger.LocalAttachTool
import com.fixcode.intellij.lua.debugger.resources.DebuggerResourceService
import java.io.File
import java.io.IOException
import javax.swing.Icon

/** 「启动并附加」工厂（Windows）：从 IDE 启动 UE 编辑器 / 游戏，在它跑任何代码前装好调试组件。 */
class LuaLaunchConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = ID
    override fun getName(): String = LuaDebugBundle.message("launch.factory.name")
    override fun getIcon(): Icon = AllIcons.Actions.Execute
    override fun createTemplateConfiguration(project: Project): RunConfiguration = LuaLaunchConfiguration(project, this)
    override fun getOptionsClass(): Class<LuaLaunchOptions> = LuaLaunchOptions::class.java

    companion object {
        const val ID = "EmmyLua.Launch"
    }
}

class LuaLaunchOptions : RunConfigurationOptions() {
    var program by string("")
    var programArguments by string("")
    /** 空 = 程序所在目录。 */
    var workingDirectory by string("")
    /** 结束调试时关掉启动的程序（UE 编辑器有没保存的改动时要小心，所以可以关掉）。 */
    var terminateOnStop by property(true)
    var captureOutput by property(true)
}

/**
 * 「启动并附加」：以调试方式启动程序，停在 exe 入口点时注入调试组件再放行（emmy_tool launch，协议 v3 第 16 节）。
 * 和先开游戏再附加相比，程序里第一个 Lua VM 建出来时调试器一定已经就位，启动阶段的 Lua（模块顶层、GameInstance 初始化）也能断住。
 * 启动后就是一个普通的本机附加会话，断线重连、MCP 都照常。
 */
class LuaLaunchConfiguration(project: Project, factory: ConfigurationFactory) :
    RunConfigurationBase<LuaLaunchOptions>(project, factory, null), LuaDebugTargetConfiguration {

    public override fun getOptions(): LuaLaunchOptions = super.getOptions() as LuaLaunchOptions

    override fun getConfigurationEditor(): SettingsEditor<LuaLaunchConfiguration> = LuaLaunchSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = NoProcessState

    override fun checkConfiguration() {
        if (!SystemInfo.isWindows) throw RuntimeConfigurationError(LuaDebugBundle.message("launch.error.windowsOnly"))
        if (options.program.isNullOrBlank()) throw RuntimeConfigurationError(LuaDebugBundle.message("launch.error.noProgram"))
    }

    override fun resolveTarget(env: ExecutionEnvironment): LuaDebugTarget {
        if (!SystemInfo.isWindows) throw ExecutionException(LuaDebugBundle.message("launch.error.windowsOnly"))
        val program = expand(options.program).ifBlank { throw ExecutionException(LuaDebugBundle.message("launch.error.noProgram")) }
        val exe = File(program)
        if (!exe.isFile) throw ExecutionException(LuaDebugBundle.message("launch.error.notFound", exe.path))
        val workDir = expand(options.workingDirectory).takeIf { it.isNotBlank() }?.let(::File) ?: exe.parentFile
        if (workDir != null && !workDir.isDirectory) throw ExecutionException(LuaDebugBundle.message("launch.error.noWorkDir", workDir.path))
        val args = ProgramParametersUtil.expandPathAndMacros(options.programArguments.orEmpty(), null, project) ?: ""
        val token = LocalAttachTool.newToken()
        val result = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<LocalAttachTool.Result, IOException>(
                {
                    val root = DebuggerResourceService.emmyNativeToolsDirectory().toFile()
                    LocalAttachTool(root).launch(exe, args, workDir, token)
                },
                LuaDebugBundle.message("launch.progress", exe.name),
                false,
                project,
            )
        } catch (e: IOException) {
            throw ExecutionException(e.message ?: e.javaClass.simpleName, e)
        }
        return LuaDebugTarget.LocalProcess(
            pid = result.pid,
            processName = exe.name,
            executablePath = exe.path,
            captureOutput = options.captureOutput,
            authToken = token,
            terminateOnStop = options.terminateOnStop,
        )
    }

    private fun expand(path: String?): String =
        path?.trim()?.takeIf { it.isNotEmpty() }?.let { ProgramParametersUtil.expandPathAndMacros(it, null, project) }.orEmpty()
}

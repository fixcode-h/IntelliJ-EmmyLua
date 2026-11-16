package com.fixcode.intellij.lua.debug.ui.device

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.api.LuaDeviceException
import com.fixcode.intellij.lua.debug.api.LuaDeviceService
import com.fixcode.intellij.lua.debug.api.LuaDeviceState
import com.fixcode.intellij.lua.debug.api.SourceMappingConfig
import com.fixcode.intellij.lua.debug.api.SourceMappingRule
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.LuaDebugIcons
import com.fixcode.intellij.lua.debug.ui.session.LuaDebugTargetConfiguration
import com.fixcode.intellij.lua.debug.ui.session.NoProcessState

/** 「Lua 真机附加」运行配置（设计文档 10.3）。 */
class LuaDeviceConfigurationType : ConfigurationTypeBase(
    ID,
    LuaDebugBundle.message("device.config.name"),
    LuaDebugBundle.message("device.config.description"),
    NotNullLazyValue.createValue { LuaDebugIcons.DEVICE },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                LuaDeviceConfiguration(project, this)
            override fun getOptionsClass(): Class<LuaDeviceOptions> = LuaDeviceOptions::class.java
        })
    }

    companion object {
        const val ID = "EmmyLua.Device"
    }
}

class SourceMappingRowState : BaseState() {
    var devicePrefix by string("")
    /** 可以写 $PROJECT_DIR$，运行时展开。 */
    var localDirectory by string("")
}

class LuaDeviceOptions : RunConfigurationOptions() {
    /** 空 = 运行时只有一台在线设备就用它。 */
    var deviceSerial by string("")
    var packageName by string("")
    var launchAndAttach by property(false)
    /** 空 = 按设置里的搜索目录自动找。 */
    var symbolFile by string("")
    var mappings by list<SourceMappingRowState>()
    var autoLearnMapping by property(true)

    /** 高级：手动地址（内嵌 runtime / 局域网 / 已有转发），开启后忽略设备和进程。 */
    var useManualAddress by property(false)
    var manualHost by string("127.0.0.1")
    var manualPort by property(9966)
    var pairingCode by string("")
}

class LuaDeviceConfiguration(project: Project, factory: ConfigurationFactory) :
    RunConfigurationBase<LuaDeviceOptions>(project, factory, null), LuaDebugTargetConfiguration {

    public override fun getOptions(): LuaDeviceOptions = super.getOptions() as LuaDeviceOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = LuaDeviceSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = NoProcessState

    override fun checkConfiguration() {
        val o = options
        if (o.useManualAddress) {
            if (o.manualHost.isNullOrBlank()) throw RuntimeConfigurationError(LuaDebugBundle.message("device.error.noHost"))
            if (o.manualPort !in 1..65535) throw RuntimeConfigurationError(LuaDebugBundle.message("device.error.badPort"))
            return
        }
        if (o.packageName.isNullOrBlank()) throw RuntimeConfigurationError(LuaDebugBundle.message("device.error.noPackage"))
        if (LuaDeviceService.getInstance() == null) {
            throw RuntimeConfigurationError(LuaDebugBundle.message("device.error.noService"))
        }
    }

    fun mappingConfig(): SourceMappingConfig = SourceMappingConfig(
        rules = options.mappings
            .filter { !it.devicePrefix.isNullOrBlank() && !it.localDirectory.isNullOrBlank() }
            .map { SourceMappingRule(it.devicePrefix!!.trim(), expand(it.localDirectory!!.trim())) },
        autoLearn = options.autoLearnMapping,
    )

    fun expand(path: String): String = project.basePath?.let { path.replace(PROJECT_DIR, it) } ?: path

    override fun resolveTarget(env: ExecutionEnvironment): LuaDebugTarget {
        checkConfigurationOrThrow()
        val o = options
        if (o.useManualAddress) {
            return LuaDebugTarget.Address(
                host = o.manualHost!!.trim(),
                port = o.manualPort,
                pairingCode = o.pairingCode?.trim()?.takeIf { it.isNotEmpty() },
                sourceMapping = mappingConfig(),
            )
        }
        val serial = o.deviceSerial?.trim().orEmpty().ifEmpty { pickOnlyDevice() }
        return LuaDebugTarget.AndroidDevice(
            deviceSerial = serial,
            packageName = o.packageName!!.trim(),
            pid = null,
            launchAndAttach = o.launchAndAttach,
            symbolFile = o.symbolFile?.trim()?.takeIf { it.isNotEmpty() }?.let { expand(it) },
            sourceMapping = mappingConfig(),
        )
    }

    private fun checkConfigurationOrThrow() {
        try {
            checkConfiguration()
        } catch (e: RuntimeConfigurationError) {
            throw ExecutionException(e.localizedMessage)
        }
    }

    /** 配置里没指定设备：只有一台在线就用它，否则让用户去配置里选。 */
    private fun pickOnlyDevice(): String {
        val service = LuaDeviceService.getInstance()
            ?: throw ExecutionException(LuaDebugBundle.message("device.error.noService"))
        val devices = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<List<com.fixcode.intellij.lua.debug.api.LuaDevice>, Exception>(
                { service.listDevices() },
                LuaDebugBundle.message("device.progress.listing"),
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            throw ExecutionException(LuaDebugBundle.message("attach.config.error.cancelled"))
        } catch (e: LuaDeviceException) {
            throw ExecutionException(e.message, e)
        }
        val online = devices.filter { it.state == LuaDeviceState.ONLINE }
        return when {
            online.size == 1 -> online.single().serial
            online.isEmpty() && devices.any { it.state == LuaDeviceState.UNAUTHORIZED } ->
                throw ExecutionException(LuaDebugBundle.message("device.error.unauthorized"))
            online.isEmpty() -> throw ExecutionException(LuaDebugBundle.message("device.error.noDevice"))
            else -> throw ExecutionException(LuaDebugBundle.message("device.error.multipleDevices", online.size))
        }
    }

    companion object {
        const val PROJECT_DIR = "\$PROJECT_DIR\$"
    }
}

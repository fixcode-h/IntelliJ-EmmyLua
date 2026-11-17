package com.fixcode.intellij.lua.debug.ui.session

import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.LuaDebugIcons
import com.fixcode.intellij.lua.debug.ui.attach.LuaAttachConfigurationFactory
import com.fixcode.intellij.lua.debug.ui.attach.LuaLaunchConfigurationFactory
import com.fixcode.intellij.lua.debug.ui.device.LuaDeviceConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.openapi.util.NotNullLazyValue

/**
 * 「添加新配置」里只有一个可折叠的「Lua」，下面是「附加到进程」「启动并附加」（Windows）和「Android 真机附加」（参照 Docker）。
 *
 * 类型 ID 用旧「Lua 附加」的 EmmyLua.Attach、工厂 ID 也不变，所以 2.3.x 建的附加配置直接归到这里；
 * 旧的真机配置由 LuaLegacyDeviceConfigurationType（隐藏）继续加载。
 */
class LuaRunConfigurationType : ConfigurationTypeBase(
    ID,
    LuaDebugBundle.message("run.type.name"),
    LuaDebugBundle.message("run.type.description"),
    NotNullLazyValue.createValue { LuaDebugIcons.LUA },
) {
    init {
        addFactory(LuaAttachConfigurationFactory(this))
        addFactory(LuaLaunchConfigurationFactory(this))
        addFactory(LuaDeviceConfigurationFactory(this))
    }

    companion object {
        const val ID = "EmmyLua.Attach"
    }
}

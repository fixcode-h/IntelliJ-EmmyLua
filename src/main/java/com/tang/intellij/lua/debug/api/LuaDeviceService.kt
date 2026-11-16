/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.debug.api

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * 真机相关的查询：设备列表、设备上的进程、符号文件、环境检查。
 * 由「Android 真机附加」实现并注册到 `com.tang.intellij.lua.debug.deviceService`。
 *
 * 所有方法都可能调 adb、读大文件，**会阻塞**：UI 只在后台线程调用，
 * 实现不需要也不应该自己切线程。失败时抛 [LuaDeviceException]，message 用中文说清楚怎么办。
 */
interface LuaDeviceService {
    /** adb devices -l；没有 adb 时抛异常（UI 会提示去设置里指定路径）。 */
    fun listDevices(): List<LuaDevice>

    /**
     * 设备上的进程。[onlyDebuggable] 为 true 时只返回可附加的（可调试包）。
     * 实现可以顺便识别 UE 游戏，填进 [LuaDeviceProcess.tags]。
     */
    fun listProcesses(serial: String, onlyDebuggable: Boolean = true): List<LuaDeviceProcess>

    /** 已安装的可调试包，给"启动应用并在第一行暂停"用（应用还没跑，进程列表里没有）。 */
    fun listDebuggablePackages(serial: String): List<String>

    /**
     * 按设置里的搜索目录找本地未裁剪 so；[configured] 非空时直接用它。找不到返回 null。
     * 有设备和包名时实现可以按设备上 so 的 Build ID 挑匹配的那一个。
     */
    fun resolveSymbolFile(project: Project, configured: String?, serial: String?, packageName: String?): String?

    /** 读 so 的 Build ID 和 lua_* 符号；给了设备和包名时同时和设备上的 so 比对。 */
    fun inspectSymbols(symbolFile: String, serial: String?, packageName: String?): LuaSymbolReport

    /** 「检查环境」按钮：逐项检查，失败项说清原因和怎么修。 */
    fun checkEnvironment(project: Project, target: LuaDebugTarget.AndroidDevice): List<LuaEnvCheckItem>

    companion object {
        val EP: ExtensionPointName<LuaDeviceService> =
            ExtensionPointName.create("com.tang.intellij.lua.debug.deviceService")

        fun getInstance(): LuaDeviceService? = EP.extensionList.firstOrNull()
    }
}

class LuaDeviceException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class LuaDeviceTransport { USB, WIFI, EMULATOR, UNKNOWN }

enum class LuaDeviceState {
    ONLINE,
    /** 手机上还没点"允许 USB 调试"。 */
    UNAUTHORIZED,
    OFFLINE,
}

data class LuaDevice(
    val serial: String,
    val model: String,
    val transport: LuaDeviceTransport,
    val state: LuaDeviceState,
    /** 例如 "14"；离线或未授权时可能为空。 */
    val androidVersion: String? = null,
    val apiLevel: Int? = null,
    /** 主 ABI，例如 arm64-v8a。 */
    val abi: String? = null,
) {
    /** Android 9（API 28）起才有 attach-agent。 */
    val supportsAttachAgent: Boolean get() = (apiLevel ?: 0) >= 28
}

data class LuaDeviceProcess(
    val pid: Int,
    val packageName: String,
    val debuggable: Boolean,
    /** 例如 "UE 游戏"；由实现识别，UI 原样显示。 */
    val tags: List<String> = emptyList(),
)

data class LuaSymbolReport(
    val symbolFile: String,
    val buildId: String?,
    /** 设备上的 Build ID；没比对时为空。 */
    val deviceBuildId: String? = null,
    val luaFunctionCount: Int = 0,
    /** 识别到的 Lua 版本，例如 "5.4"。 */
    val luaVersion: String? = null,
    /** 读文件失败、不是 ELF、没有 lua_* 符号时的说明。 */
    val error: String? = null,
    /** 附加必需但 so 里找不到的函数。 */
    val missingFunctions: List<String> = emptyList(),
    /** 不致命的问题，例如 so 里有两份 Lua；UI 原样显示。 */
    val warnings: List<String> = emptyList(),
) {
    /** null = 没比对（没连设备）。 */
    val buildIdMatches: Boolean?
        get() = if (buildId == null || deviceBuildId == null) null else buildId.equals(deviceBuildId, ignoreCase = true)
}

enum class LuaCheckStatus { OK, WARNING, FAILED }

data class LuaEnvCheckItem(
    val title: String,
    val status: LuaCheckStatus,
    /** 一句话结果，例如"Android 14 支持 attach-agent"。 */
    val detail: String,
    /** 失败时怎么修；成功时为空。 */
    val fix: String? = null,
)

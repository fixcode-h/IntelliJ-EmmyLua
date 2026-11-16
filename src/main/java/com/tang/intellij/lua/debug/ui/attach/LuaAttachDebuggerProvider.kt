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

package com.tang.intellij.lua.debug.ui.attach

import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import com.intellij.xdebugger.attach.XAttachHost
import com.intellij.xdebugger.attach.XAttachPresentationGroup
import com.intellij.xdebugger.attach.XAttachProcessPresentationGroup
import com.intellij.xdebugger.attach.XLocalAttachDebugger
import com.tang.intellij.lua.debug.api.LuaDebugTarget
import com.tang.intellij.lua.debug.api.LuaLocalProcess
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import com.tang.intellij.lua.debug.ui.LuaDebugIcons
import com.tang.intellij.lua.debug.ui.process.LuaLocalProcessScanner
import com.tang.intellij.lua.debug.ui.session.LuaDebugSessionStarter
import com.tang.intellij.lua.debug.ui.settings.LuaDebugProjectState
import javax.swing.Icon

/**
 * `Run > Attach to Process…` 里的「Lua」分组（设计文档 10.2）。
 *
 * IntelliJ 对每个进程调一次 [getAvailableDebuggers]，所以 Lua 检测按对话框做一次批量扫描，
 * 结果缓存在对话框的 contextHolder 里；刷新列表时 IntelliJ 会换一个新的 holder。
 */
class LuaAttachDebuggerProvider : XAttachDebuggerProvider {

    override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is LocalAttachHost

    override fun getPresentationGroup(): XAttachPresentationGroup<ProcessInfo> = LuaProcessGroup

    override fun getAvailableDebuggers(
        project: Project,
        attachHost: XAttachHost,
        processInfo: ProcessInfo,
        contextHolder: UserDataHolder,
    ): List<XAttachDebugger> {
        val scanned = scanOnce(attachHost, contextHolder)
        val process = scanned[processInfo.pid] ?: return emptyList()
        if (!LuaLocalProcessScanner.isLuaCandidate(process)) return emptyList()
        return listOf(LuaLocalAttachDebugger(process))
    }

    private fun scanOnce(host: XAttachHost, holder: UserDataHolder): Map<Int, LuaLocalProcess> {
        holder.getUserData(SCAN_KEY)?.let { return it }
        val processes = try {
            host.getProcessList()
        } catch (_: Exception) {
            LuaLocalProcessScanner.listProcesses()
        }
        return LuaLocalProcessScanner.scan(processes).also { holder.putUserData(SCAN_KEY, it) }
    }

    companion object {
        private val SCAN_KEY = Key.create<Map<Int, LuaLocalProcess>>("EmmyLua.Debug.LuaProcessScan")
        internal val PROCESS_KEY = SCAN_KEY
    }
}

internal class LuaLocalAttachDebugger(private val process: LuaLocalProcess) : XLocalAttachDebugger {
    override fun getDebuggerDisplayName(): String = LuaDebugBundle.message("attach.debugger.name")

    override fun attachDebugSession(project: Project, processInfo: ProcessInfo) {
        LuaDebugProjectState.getInstance(project).rememberAttach(process.executablePath ?: process.name)
        LuaDebugSessionStarter.startWithoutConfiguration(
            project,
            LuaDebugTarget.LocalProcess(
                pid = process.pid,
                processName = process.name,
                executablePath = process.executablePath,
            ),
        )
    }
}

/**
 * 分组标题"Lua（检测到 Lua 运行时）"，每行：`名字  pid  x64  Lua 5.4  UE 编辑器  最近附加`。
 * 排在 IntelliJ 自带的本机进程分组前面；"最近附加"由 IntelliJ 自己的 Recent 区块处理，这里只加个标记。
 */
internal object LuaProcessGroup : XAttachProcessPresentationGroup {
    override fun getOrder(): Int = -100

    override fun getGroupName(): String = LuaDebugBundle.message("attach.group.name")

    override fun getItemIcon(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): Icon = LuaDebugIcons.LUA

    override fun getItemDisplayText(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): String {
        val process = dataHolder.getUserData(LuaAttachDebuggerProvider.PROCESS_KEY)?.get(info.pid)
            ?: return info.executableDisplayName
        val parts = mutableListOf(process.name)
        process.arch?.let { parts += it }
        process.luaVersion?.let { parts += if (it.startsWith("Lua")) it else "Lua $it" }
        parts += LuaLocalProcessScanner.tags(process)
        if (isRecent(project, process)) parts += LuaDebugBundle.message("attach.recent")
        return parts.joinToString("  ")
    }

    override fun getItemDescription(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): String? =
        dataHolder.getUserData(LuaAttachDebuggerProvider.PROCESS_KEY)?.get(info.pid)?.executablePath

    override fun compare(a: ProcessInfo, b: ProcessInfo): Int =
        a.executableDisplayName.compareTo(b.executableDisplayName, ignoreCase = true).takeIf { it != 0 }
            ?: a.pid.compareTo(b.pid)

    private fun isRecent(project: Project, process: LuaLocalProcess): Boolean =
        LuaDebugProjectState.getInstance(project).recentAttachTargets
            .any { it == process.executablePath || it == process.name }
}

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

package com.tang.intellij.lua.debugger.emmy

import com.google.gson.Gson
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.tang.intellij.lua.debugger.*
import com.tang.intellij.lua.psi.LuaFileManager
import com.tang.intellij.lua.psi.LuaFileUtil
import com.tang.intellij.lua.project.LuaSettings
import java.io.File

abstract class EmmyDebugProcessBase(session: XDebugSession) : LuaDebugProcess(session), ITransportHandler {
    private val editorsProvider = LuaDebuggerEditorsProvider()
    private val evalHandlers = mutableListOf<IEvalResultHandler>()
    private val breakpoints = mutableMapOf<Int, BreakPoint>()
    private var idCounter = 0;
    protected var transporter: Transporter? = null

    companion object {
        private val ID = Key.create<Int>("lua.breakpoint")
    }

    override fun sessionInitialized() {
        super.sessionInitialized()
        ApplicationManager.getApplication().executeOnPooledThread {
            setupTransporter()
        }
    }

    protected abstract fun setupTransporter()

    private fun sendInitReq() {
        // 在后台线程执行文件读取操作，避免EDT违规
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 清理旧会话的断点状态，确保新会话从干净状态开始
                resetBreakpointState()
                
                // send init - 发送 emmyHelper 目录路径、自定义目录路径和脚本名称
                val emmyHelperPath = getEmmyHelperDirPath()
                val customHelperPath = getCustomHelperDirPath()
                val emmyHelperExtName = getEmmyHelperExtName()
                
                if (emmyHelperPath != null) {
                    val extList = LuaFileManager.extensions
                    transporter?.send(InitMessage(
                        emmyHelperPath = emmyHelperPath,
                        customHelperPath = customHelperPath,
                        emmyHelperName = "emmyHelper",
                        emmyHelperExtName = emmyHelperExtName,
                        ext = extList
                    ))
                } else {
                    error("无法获取 emmyHelper 目录路径")
                }
                
                // send bps - 重新同步所有断点到调试器端
                val breakpoints = XDebuggerManager.getInstance(session.project)
                    .breakpointManager
                    .getBreakpoints(LuaLineBreakpointType::class.java)
                breakpoints.forEach { breakpoint ->
                    breakpoint.sourcePosition?.let { position ->
                        registerBreakpoint(position, breakpoint)
                    }
                }
                // send ready
                transporter?.send(Message(MessageCMD.ReadyReq))
            } catch (e: Exception) {
                error("发送初始化请求失败: ${e.message}")
            }
        }
    }
    
    /**
     * 重置断点状态，用于新调试会话开始时清理旧状态
     * 这确保了：
     * 1. ID计数器从0开始，避免ID无限增长
     * 2. 断点映射被清空，避免残留的旧断点数据
     * 3. 所有断点的userData中的ID被清除，确保重新注册时获得正确的新ID
     */
    private fun resetBreakpointState() {
        // 清空本地断点映射
        breakpoints.clear()
        // 重置ID计数器
        idCounter = 0
        
        // 清除所有断点的旧ID userData
        // 这很重要，因为断点对象是IDE持久化的，userData会跨会话保留
        try {
            val allBreakpoints = XDebuggerManager.getInstance(session.project)
                .breakpointManager
                .getBreakpoints(LuaLineBreakpointType::class.java)
            allBreakpoints.forEach { breakpoint ->
                breakpoint.putUserData(ID, null)
            }
        } catch (e: Exception) {
            // 忽略清理userData时的异常，不影响主流程
        }
    }
    
    /**
     * 获取 emmyHelper 目录路径
     * 
     * 支持开发模式和生产模式：
     * - 开发模式：直接返回 src/main/resources/debugger/emmy 目录路径
     * - 生产模式：将资源解压到临时目录并返回路径
     */
    private fun getEmmyHelperDirPath(): String? {
        // 1. 尝试开发模式路径
        val devPath = getDevResourcePath("debugger/emmy")
        if (devPath != null) {
            return devPath
        }
        
        // 2. 生产模式：解压资源到临时目录
        return extractEmmyHelperToTemp()
    }
    
    /**
     * 获取开发模式下的资源目录路径
     */
    private fun getDevResourcePath(relativePath: String): String? {
        val basePath = session.project.basePath ?: return null
        val devResourceDir = File(basePath, "src/main/resources/$relativePath")
        if (devResourceDir.exists() && devResourceDir.isDirectory) {
            return devResourceDir.absolutePath
        }
        return null
    }
    
    /**
     * 将 emmyHelper 资源解压到临时目录
     */
    private fun extractEmmyHelperToTemp(): String? {
        try {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "emmy_helper")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            
            // 需要解压的文件列表
            val filesToExtract = listOf("emmyHelper.lua", "emmyHelper_ue.lua")
            
            for (fileName in filesToExtract) {
                val resourcePath = "debugger/emmy/$fileName"
                val content = readPluginResource(resourcePath)
                if (content != null) {
                    val targetFile = File(tempDir, fileName)
                    targetFile.writeText(content)
                }
            }
            
            return tempDir.absolutePath
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * 获取自定义 helper 目录路径
     * 
     * 如果用户配置了自定义脚本，返回其所在目录路径
     */
    private fun getCustomHelperDirPath(): String {
        val settings = LuaSettings.instance
        val customPath = settings.customHelperPath
        
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.isDirectory) {
                return customFile.absolutePath
            }
        }
        
        return ""
    }
    
    /**
     * 获取扩展脚本名称
     * 
     * 如果用户配置了自定义扩展脚本名称，返回该名称
     * 否则返回默认的 "emmyHelper_ue"
     */
    private fun getEmmyHelperExtName(): String {
        val settings = LuaSettings.instance
        val customExtName = settings.customHelperExtName
        
        return if (!customExtName.isNullOrBlank()) {
            customExtName
        } else {
            "emmyHelper_ue"
        }
    }
    
    /**
     * 读取插件资源文件内容，支持开发模式、JAR包和文件系统
     * 
     * 开发模式路径自动检测逻辑：
     * 1. 获取当前项目根目录 (session.project.basePath)
     * 2. 检查是否存在 src/main/resources 目录
     * 3. 如果存在且开发模式已启用，则从该目录读取
     * 4. 否则回退到从 JAR 包读取
     */
    private fun readPluginResource(path: String): String? {
        val settings = LuaSettings.instance
        
        // 开发模式：自动检测项目源码目录
        if (settings.enableDevMode) {
            val projectBasePath = session.project.basePath
            if (projectBasePath != null) {
                // 尝试标准 Maven/Gradle 项目结构
                val resourceDir = File(projectBasePath, "src/main/resources")
                if (resourceDir.exists() && resourceDir.isDirectory) {
                    val devFile = File(resourceDir, path)
                    if (devFile.exists() && devFile.isFile) {
                        return try {
                            val content = devFile.readText()
                            println("✅ [开发模式] 从项目源码读取: ${devFile.absolutePath}")
                            content
                        } catch (e: Exception) {
                            println("⚠️ [开发模式] 读取失败: ${devFile.absolutePath}, ${e.message}")
                            null
                        }
                    }
                }
            }
        }
        
        return try {
            // 首先尝试使用类加载器从JAR包中读取
            val classLoader = LuaFileUtil::class.java.classLoader
            val resource = classLoader.getResource(path)
            if (resource != null) {
                val content = resource.readText()
                content
            } else {
                // 如果类加载器无法找到，尝试使用LuaFileUtil的方法
                val filePath = LuaFileUtil.getPluginVirtualFile(path)
                if (filePath != null && !filePath.startsWith("jar:")) {
                    File(filePath).readText()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            error("读取插件资源失败: $path, ${e.message}")
            null
        }
    }

    override fun onConnect(suc: Boolean) {
        if (suc) {
            ApplicationManager.getApplication().runReadAction {
                sendInitReq()
            }
        } else stop()
    }

    override fun onDisconnect() {
        stop()
        session?.stop()
    }

    override fun onReceiveMessage(cmd: MessageCMD, json: String) {
        when (cmd) {
            MessageCMD.BreakNotify -> {
                val data = Gson().fromJson(json, BreakNotify::class.java)
                onBreak(data)
            }

            MessageCMD.EvalRsp -> {
                val rsp = Gson().fromJson(json, EvalRsp::class.java)
                onEvalRsp(rsp)
            }

            MessageCMD.LogNotify -> {
                val notify = Gson().fromJson(json, LogNotify::class.java)
                // type: 0=Debug, 1=Info, 2=Warning, 3=Error
                val contentType = when (notify.type) {
                    0 -> ConsoleViewContentType.LOG_DEBUG_OUTPUT    // Debug
                    1 -> ConsoleViewContentType.SYSTEM_OUTPUT       // Info
                    2 -> ConsoleViewContentType.LOG_WARNING_OUTPUT  // Warning
                    3 -> ConsoleViewContentType.ERROR_OUTPUT        // Error
                    else -> ConsoleViewContentType.SYSTEM_OUTPUT
                }
                println(notify.message, LogConsoleType.NORMAL, contentType)
            }

            else -> {
                println("Unknown message: $cmd")
            }
        }
    }

    override fun registerBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val file = sourcePosition.file
        val shortPath = file.canonicalPath
        if (shortPath != null) {
            val newId = idCounter++
            breakpoint.putUserData(ID, newId)

            if (breakpoint.isLogMessage) {
                breakpoints[newId] =
                    BreakPoint(shortPath, breakpoint.line + 1, null, breakpoint.logExpressionObject?.expression)
            } else {
                breakpoints[newId] =
                    BreakPoint(shortPath, breakpoint.line + 1, breakpoint.conditionExpression?.expression)
            }
            val bp = breakpoints.getOrDefault(newId, null)
            if (bp != null) {
                send(AddBreakPointReq(listOf(bp)))
            }
        }
    }

    override fun unregisterBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val file = sourcePosition.file
        val shortPath = file.canonicalPath
        if (shortPath != null) {
            val id = breakpoint.getUserData(ID)
            val bp = breakpoints.getOrDefault(id, null)
            if (bp != null) {
                breakpoints.remove(id)
                send(RemoveBreakPointReq(listOf(bp)))
            }
        }
    }

    override fun startPausing() {
        send(DebugActionMessage(DebugAction.Break))
    }

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        send(AddBreakPointReq(listOf(BreakPoint(position.file.path, position.line + 1, null, null, null))))
        send(DebugActionMessage(DebugAction.Continue))
    }

    private fun onBreak(data: BreakNotify) {
        evalHandlers.clear()
        val frames = data.stacks.map { EmmyDebugStackFrame(it, this) }
        val top = frames.firstOrNull { it.sourcePosition != null }
            ?: frames.firstOrNull { it.data.line > 0 }
            ?: frames.firstOrNull()
        val stack = LuaExecutionStack(frames)
        if (top != null)
            stack.setTopFrame(top)
        val breakpoint = top?.sourcePosition?.let { getBreakpoint(it.file, it.line) }
        if (breakpoint != null) {
            ApplicationManager.getApplication().invokeLater {
                session.breakpointReached(breakpoint, null, LuaSuspendContext(stack))
                session.showExecutionPoint()
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                // todo: fix "Internal classes usages" & "Internal methods usages" problem
                /*val se = session
                if (se is XDebugSessionImpl)
                    se.positionReached(LuaSuspendContext(stack), true)
                else
                    se.positionReached(LuaSuspendContext(stack))*/
                session.positionReached(LuaSuspendContext(stack))
                session.showExecutionPoint()
            }
        }
    }

    private fun onEvalRsp(rsp: EvalRsp) {
        evalHandlers.forEach { it.handleMessage(rsp) }
    }

    override fun run() {
        send(DebugActionMessage(DebugAction.Continue))
    }

    override fun stop() {
        send(DebugActionMessage(DebugAction.Stop))
        send(StopSign())
        transporter?.close()
        transporter = null
    }

    override fun startStepOver(context: XSuspendContext?) {
        send(DebugActionMessage(DebugAction.StepOver))
    }

    override fun startStepInto(context: XSuspendContext?) {
        send(DebugActionMessage(DebugAction.StepIn))
    }

    override fun startStepOut(context: XSuspendContext?) {
        send(DebugActionMessage(DebugAction.StepOut))
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    fun addEvalResultHandler(handler: IEvalResultHandler) {
        evalHandlers.add(handler)
    }

    fun removeMessageHandler(handler: IEvalResultHandler) {
        evalHandlers.remove(handler)
    }

    fun send(msg: IMessage) {
        transporter?.send(msg)
    }
}
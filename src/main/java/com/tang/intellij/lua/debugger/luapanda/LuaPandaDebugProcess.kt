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

package com.tang.intellij.lua.debugger.luapanda

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.ui.DebuggerColors
import com.tang.intellij.lua.debugger.*
import com.tang.intellij.lua.psi.LuaFileUtil
import com.tang.intellij.lua.LuaBundle
import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LuaPanda调试进程
 * 负责管理调试会话、传输器连接、断点管理和调试控制
 */
class LuaPandaDebugProcess(session: XDebugSession) : LuaDebugProcess(session) {
    
    // ========== 属性定义 ==========
    
    private val configuration = session.runProfile as LuaPandaDebugConfiguration
    private val editorsProvider = LuaDebuggerEditorsProvider()
    private val breakpoints = mutableMapOf<Int, LuaPandaBreakpoint>()
    private var idCounter = 0
    internal var transporter: LuaPandaTransporter? = null
    private val logger = Logger.getInstance(LuaPandaDebugProcess::class.java)
    private var isInitialized = false
    private val isClientMode = configuration.transportType == LuaPandaTransportType.TCP_CLIENT

    companion object {
        private val ID = Key.create<Int>("luapanda.breakpoint")
    }
    
    // ========== 生命周期管理 ==========
    
    override fun sessionInitialized() {
        super.sessionInitialized()
        logWithLevel("调试会话初始化完成", LogLevel.CONNECTION)
        ApplicationManager.getApplication().executeOnPooledThread {
            setupTransporter()
        }
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    override fun stop() {
        logWithLevel("停止调试", LogLevel.DEBUG)
        
        if (transporter != null) {
            try {
                val stopConfirmed = AtomicBoolean(false)
                
                // 发送停止命令，等待确认
                sendCommandWithResponse(LuaPandaCommands.STOP_RUN, null)
                
                // 设置超时机制
                setupStopTimeout(stopConfirmed)
                
            } catch (e: Exception) {
                logWithLevel("发送停止命令时出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                e.printStackTrace()
                stopTransporter()
            }
        } else {
            logWithLevel("transporter为null，无法发送stopRun命令", LogLevel.DEBUG)
        }
    }
    
    // ========== 传输器管理 ==========
    
    private fun setupTransporter() {
        val transportInfo = when (configuration.transportType) {
            LuaPandaTransportType.TCP_CLIENT -> "TCP客户端 ${configuration.host}:${configuration.port}"
            LuaPandaTransportType.TCP_SERVER -> "TCP服务器 端口:${configuration.port}"
        }
        logWithLevel("设置传输器: $transportInfo", LogLevel.DEBUG)
        startTransporter()
    }
    
    private fun startTransporter() {
        // 停止现有传输器
        transporter?.stop()
        
        transporter = when (configuration.transportType) {
            LuaPandaTransportType.TCP_CLIENT -> {
                LuaPandaTcpClientTransporter(configuration.host, configuration.port, configuration.autoReconnect, this)
            }
            LuaPandaTransportType.TCP_SERVER -> {
                LuaPandaTcpServerTransporter(configuration.port, this)
            }
        }
        
        setupTransporterHandlers()
        
        try {
            transporter?.start()
        } catch (e: Exception) {
            logWithLevel("传输器启动失败: ${e.message}", LogLevel.CONNECTION, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            logger.error("Failed to start transporter", e)
            onDisconnect()
        }
    }
    
    private fun setupTransporterHandlers() {
        transporter?.setLogLevel(configuration.logLevel)
        
        transporter?.setMessageHandler { message ->
            handleMessage(message)
        }
        
        transporter?.setConnectionHandler { connected ->
            logWithLevel("连接状态: ${if (connected) "已连接" else "已断开"}", LogLevel.CONNECTION)
            if (connected) {
                onConnect()
            } else {
                onDisconnect()
            }
        }
    }
    
    private fun stopTransporter() {
        transporter?.stop()
        transporter = null
    }
    
    private fun setupStopTimeout(stopConfirmed: AtomicBoolean) {
        Thread {
            try {
                val timeoutMs = (configuration.stopConfirmTimeout * 1000).toLong()
                logWithLevel("等待停止确认，超时时间: ${configuration.stopConfirmTimeout}秒", LogLevel.CONNECTION)
                Thread.sleep(timeoutMs)
                
                if (!stopConfirmed.get() && transporter != null) {
                    logWithLevel("停止确认超时，强制关闭连接", LogLevel.CONNECTION)
                    stopTransporter()
                }
            } catch (e: InterruptedException) {
                logWithLevel("停止确认等待被中断", LogLevel.DEBUG)
                Thread.currentThread().interrupt()
            }
        }.start()
    }
    
    // ========== 连接管理 ==========
    
    private fun onConnect() {
        logWithLevel("连接建立，等待连接稳定后发送初始化消息...", LogLevel.DEBUG)
        
        // 延迟发送初始化消息，确保连接稳定
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(1000) // 给Lua端更多准备时间
                ApplicationManager.getApplication().invokeLater {
                    if (transporter != null && !session.isStopped) {
                        sendInitializationMessageWithRetry(1)
                    }
                }
            } catch (e: InterruptedException) {
                logWithLevel("初始化消息发送被中断", LogLevel.DEBUG)
            }
        }
    }

    private fun onDisconnect() {
        logWithLevel("连接断开", LogLevel.CONNECTION)
        isInitialized = false
        logWithLevel("客户端断开连接，等待重新连接...", LogLevel.CONNECTION)
    }
    
    // ========== 初始化管理 ==========
    
    private fun sendInitializationMessageWithRetry(attempt: Int) {
        if (attempt > 3) {
            logWithLevel("初始化消息发送失败，已达到最大重试次数", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            return
        }
        
        logWithLevel("发送初始化消息 (尝试 $attempt/3)...", LogLevel.DEBUG)
        
        try {
            sendInitializationMessage()
        } catch (e: Exception) {
            handleInitializationError(e, attempt)
        }
    }
    
    private fun handleInitializationError(e: Exception, attempt: Int) {
        logWithLevel("初始化消息发送失败: ${e.javaClass.simpleName} - ${e.message}, 将在1秒后重试", LogLevel.DEBUG)
        
        when (e) {
            is java.net.SocketException -> {
                logWithLevel("Socket异常，可能是连接已断开: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            }
            is java.io.IOException -> {
                logWithLevel("IO异常，可能是网络问题: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            }
            else -> {
                logWithLevel("未知异常: ${e.message}", LogLevel.DEBUG)
            }
        }
        
        // 延迟后重试
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(1000)
                ApplicationManager.getApplication().invokeLater {
                    if (transporter != null && !session.isStopped) {
                        sendInitializationMessageWithRetry(attempt + 1)
                    }
                }
            } catch (e: InterruptedException) {
                logWithLevel("重试被中断", LogLevel.DEBUG)
            }
        }
    }
    
    private fun sendInitializationMessage() {
        // 检查连接状态
        if (transporter == null || session.isStopped) {
            logWithLevel("传输器不可用或会话已停止，跳过初始化消息发送", LogLevel.DEBUG)
            return
        }

        val pluginPath = getPluginPath()
        val initInfo = createInitInfo(pluginPath)
        
        logWithLevel("发送初始化消息...", LogLevel.DEBUG)
        
        try {
            sendCommandWithResponse(LuaPandaCommands.INIT_SUCCESS, initInfo)
            logWithLevel("初始化消息已发送，等待Lua端响应...", LogLevel.DEBUG)
            
        } catch (e: Exception) {
            logWithLevel("发送初始化消息时出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            throw e
        }
    }
    
    private fun getPluginPath(): String {
        return try {
            val pluginDescriptor = PluginManagerCore.getPlugin(
                PluginId.getId("com.fixcode.emmylua.enhanced")
            )
            
            if (pluginDescriptor != null) {
                val pluginBasePath = pluginDescriptor.pluginPath
                val debuggerLibPath = pluginBasePath?.resolve("Debugger")?.resolve("luapanda")
                val path = debuggerLibPath?.toString()?.let { it + java.io.File.separator } ?: ""
                logWithLevel("插件路径: $path", LogLevel.DEBUG)
                path
            } else {
                logWithLevel("插件描述符为null", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                ""
            }
        } catch (e: Exception) {
            logWithLevel("获取插件路径失败: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            e.printStackTrace()
            ""
        }
    }
    
    private fun createInitInfo(pluginPath: String): LuaPandaInitInfo {
        return LuaPandaInitInfo(
            stopOnEntry = configuration.stopOnEntry.toString(),
            useCHook = configuration.useCHook.toString(),
            logLevel = configuration.logLevel.toString(),
            luaFileExtension = "lua",
            cwd = session.project.basePath ?: System.getProperty("user.dir"),
            isNeedB64EncodeStr = "true",
            TempFilePath = session.project.basePath ?: System.getProperty("user.dir"),
            pathCaseSensitivity = "true",
            OSType = System.getProperty("os.name"),
            clibPath = pluginPath,
            adapterVersion = "1.0.0",
            autoPathMode = "false",
            distinguishSameNameFile = "false",
            truncatedOPath = "",
            DevelopmentMode = "false"
        )
    }
    
    private fun handleInitializationResponse(response: LuaPandaMessage) {
        logWithLevel("收到Lua端初始化响应，调试器初始化成功！", LogLevel.CONNECTION)
        
        response.getInfoAsObject()?.let { info ->
            val useHookLib = info.get("UseHookLib")?.asString ?: "0"
            val useLoadstring = info.get("UseLoadstring")?.asString ?: "0"
            val isNeedB64EncodeStr = info.get("isNeedB64EncodeStr")?.asString ?: "false"
            
            logWithLevel("初始化完成 - HookLib:$useHookLib, Loadstring:$useLoadstring, B64:$isNeedB64EncodeStr", LogLevel.DEBUG)
            logger.info("LuaPanda initialized - UseHookLib: $useHookLib, UseLoadstring: $useLoadstring, B64Encode: $isNeedB64EncodeStr")
            
            isInitialized = true
            sendExistingBreakpoints()
        }
    }
    
    private fun sendExistingBreakpoints() {
        val breakpoints = XDebuggerManager.getInstance(session.project)
            .breakpointManager
            .getBreakpoints(LuaLineBreakpointType::class.java)
        
        if (breakpoints.isNotEmpty()) {
            logWithLevel("发送现有断点: ${breakpoints.size}个", LogLevel.DEBUG)
            breakpoints.forEach { breakpoint ->
                val sourcePosition = breakpoint.sourcePosition
                if (sourcePosition != null) {
                    val filePath = sourcePosition.file.canonicalPath ?: sourcePosition.file.path
                    val breakpointInfo = BreakpointInfo(
                        verified = true,
                        type = 2,
                        line = breakpoint.line + 1
                    )
                    val luaPandaBreakpoint = LuaPandaBreakpoint(
                        path = filePath,
                        bks = listOf(breakpointInfo)
                    )
                    sendCommandNoResponse(LuaPandaCommands.SET_BREAKPOINT, luaPandaBreakpoint)
                }
            }
        } else {
            logWithLevel("没有现有断点需要发送", LogLevel.DEBUG)
        }
    }
    
    // ========== 消息处理 ==========
    
    private fun handleMessage(message: LuaPandaMessage) {
        when (message.cmd) {
            LuaPandaCommands.STOP_ON_BREAKPOINT, 
            LuaPandaCommands.STOP_ON_ENTRY, 
            LuaPandaCommands.STEP_OVER, 
            LuaPandaCommands.STEP_IN, 
            LuaPandaCommands.STEP_OUT -> {
                handleBreakMessage(message)
            }
            LuaPandaCommands.STOP_RUN -> {
                handleStopRunMessage()
            }
            LuaPandaCommands.OUTPUT -> {
                handleOutputMessage(message)
            }
            else -> {
                logger.info("Unknown message: ${message.cmd}")
            }
        }
    }
    
    private fun handleBreakMessage(message: LuaPandaMessage) {
        val stacks = extractStacksFromMessage(message)
        
        if (stacks.isNotEmpty()) {
            val actionType = when (message.cmd) {
                LuaPandaCommands.STEP_OVER -> "单步跳过到"
                LuaPandaCommands.STEP_IN -> "单步进入到"
                LuaPandaCommands.STEP_OUT -> "单步跳出到"
                else -> "断点命中"
            }
            logWithLevel("$actionType: ${stacks[0].file}:${stacks[0].line} (${stacks.size}个堆栈帧)", LogLevel.DEBUG)
        }
        onBreak(stacks)
    }
    
    private fun extractStacksFromMessage(message: LuaPandaMessage): List<LuaPandaStack> {
        return if (message.stack != null) {
            message.stack
        } else if (message.getInfoAsObject() != null) {
            Gson().fromJson(message.getInfoAsObject(), Array<LuaPandaStack>::class.java).toList()
        } else {
            logWithLevel("警告：消息中没有找到堆栈信息", LogLevel.DEBUG, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            emptyList()
        }
    }
    
    private fun handleStopRunMessage() {
        logWithLevel("收到Lua程序停止运行命令", LogLevel.CONNECTION)
        ApplicationManager.getApplication().invokeLater {
            try {
                session?.stop()
                logWithLevel("调试会话已停止", LogLevel.CONNECTION)
            } catch (e: Exception) {
                logWithLevel("停止调试会话时出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                logger.error("Error stopping debug session on STOP_RUN", e)
            }
        }
    }
    
    private fun handleOutputMessage(message: LuaPandaMessage) {
        val outputText = message.getInfoAsObject()?.get("content")?.asString ?: ""
        logWithLevel(outputText, LogLevel.DEBUG)
    }
    
    // ========== 断点管理 ==========
    
    override fun registerBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val filePath = sourcePosition.file.canonicalPath ?: sourcePosition.file.path
        val newId = idCounter++
        
        breakpoint.putUserData(ID, newId)
        logWithLevel("设置断点: ${sourcePosition.file.name}:${breakpoint.line + 1}", LogLevel.DEBUG)
        
        val breakpointInfo = BreakpointInfo(
            verified = true,
            type = 2,
            line = breakpoint.line + 1
        )
        
        val luaPandaBreakpoint = LuaPandaBreakpoint(
            path = filePath,
            bks = listOf(breakpointInfo)
        )
        
        breakpoints[newId] = luaPandaBreakpoint
        
        if (isInitialized) {
            logWithLevel("发送断点到调试器: ${sourcePosition.file.name}:${breakpoint.line + 1}", LogLevel.DEBUG)
            sendCommandNoResponse(LuaPandaCommands.SET_BREAKPOINT, luaPandaBreakpoint)
        } else {
            logWithLevel("调试器未初始化，断点将在初始化完成后发送", LogLevel.DEBUG)
        }
    }

    override fun unregisterBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val id = breakpoint.getUserData(ID)
        val luaPandaBreakpoint = breakpoints[id]
        
        if (luaPandaBreakpoint != null) {
            logWithLevel("移除断点: ${sourcePosition.file.name}:${breakpoint.line + 1}", LogLevel.DEBUG)
            breakpoints.remove(id)
            
            val emptyBreakpoint = LuaPandaBreakpoint(
                path = luaPandaBreakpoint.path,
                bks = emptyList()
            )
            sendCommandNoResponse(LuaPandaCommands.SET_BREAKPOINT, emptyBreakpoint)
        }
    }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> {
        return arrayOf(object : XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(LuaLineBreakpointType::class.java) {
            override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
                val sourcePosition = breakpoint.sourcePosition
                if (sourcePosition != null) {
                    this@LuaPandaDebugProcess.registerBreakpoint(sourcePosition, breakpoint)
                }
            }

            override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>, temporary: Boolean) {
                val sourcePosition = breakpoint.sourcePosition
                if (sourcePosition != null) {
                    this@LuaPandaDebugProcess.unregisterBreakpoint(sourcePosition, breakpoint)
                }
            }
        })
    }
    
    // ========== 调试控制 ==========
    
    override fun startPausing() {
        sendCommandNoResponse(LuaPandaCommands.STOP_ON_BREAKPOINT)
    }

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        val filePath = position.file.canonicalPath ?: position.file.path
        val tempBreakpointInfo = BreakpointInfo(
            verified = true,
            type = 2,
            line = position.line + 1
        )
        val tempBreakpoint = LuaPandaBreakpoint(
            path = filePath,
            bks = listOf(tempBreakpointInfo)
        )
        sendCommandNoResponse(LuaPandaCommands.SET_BREAKPOINT, tempBreakpoint)
    }

    override fun run() {
        logWithLevel("继续运行", LogLevel.DEBUG)
        sendCommandNoResponse(LuaPandaCommands.CONTINUE)
    }

    override fun startStepOver(context: XSuspendContext?) {
        logWithLevel("单步跳过", LogLevel.DEBUG)
        sendCommandWithResponse(LuaPandaCommands.STEP_OVER, null)
    }

    override fun startStepInto(context: XSuspendContext?) {
        logWithLevel("单步进入", LogLevel.DEBUG)
        sendCommandWithResponse(LuaPandaCommands.STEP_IN, null)
    }

    override fun startStepOut(context: XSuspendContext?) {
        logWithLevel("单步跳出", LogLevel.DEBUG)
        sendCommandWithResponse(LuaPandaCommands.STEP_OUT, null)
    }
    
    // ========== 断点处理 ==========
    
    private fun onBreak(stacks: List<LuaPandaStack>) {
        if (stacks.isNotEmpty()) {
            val suspendContext = LuaPandaSuspendContext(this, stacks)
            val topStack = stacks[0]
            
            ApplicationManager.getApplication().invokeLater {
                val sourcePosition = createSourcePosition(topStack)
                
                setupExecutionStack(suspendContext, sourcePosition)
                handleBreakpointHit(sourcePosition, suspendContext)
                showExecutionPoint(sourcePosition)
            }
        } else {
            logger.warn("Received empty stack frames")
        }
    }
    
    private fun setupExecutionStack(suspendContext: LuaPandaSuspendContext, sourcePosition: XSourcePosition?) {
        val executionStack = suspendContext.activeExecutionStack
        if (executionStack is LuaPandaExecutionStack && sourcePosition != null) {
            val topFrame = executionStack.topFrame
            if (topFrame != null) {
                executionStack.setTopFrame(topFrame)
            }
        }
    }
    
    private fun handleBreakpointHit(sourcePosition: XSourcePosition?, suspendContext: LuaPandaSuspendContext) {
        val breakpoint = findBreakpointAtPosition(sourcePosition)
        
        if (breakpoint != null) {
            session.breakpointReached(breakpoint, null, suspendContext)
        } else {
            session.positionReached(suspendContext)
        }
        
        session.showExecutionPoint()
    }
    
    private fun findBreakpointAtPosition(sourcePosition: XSourcePosition?): XLineBreakpoint<*>? {
        return sourcePosition?.let { position ->
            val breakpointManager = XDebuggerManager.getInstance(session.project).breakpointManager
            val breakpoints = breakpointManager.getBreakpoints(LuaLineBreakpointType::class.java)
            breakpoints.find { bp ->
                bp.sourcePosition?.file == position.file && bp.sourcePosition?.line == position.line
            }
        }
    }
    
    private fun showExecutionPoint(sourcePosition: XSourcePosition?) {
        if (sourcePosition != null) {
            ApplicationManager.getApplication().invokeLater {
                val fileEditorManager = FileEditorManager.getInstance(session.project)
                val editor = fileEditorManager.openFile(sourcePosition.file, true, true)
                if (editor.isNotEmpty()) {
                    val textEditor = editor[0] as? TextEditor
                    textEditor?.editor?.let { editorInstance ->
                        val offset = editorInstance.document.getLineStartOffset(sourcePosition.line)
                        editorInstance.caretModel.moveToOffset(offset)
                        editorInstance.scrollingModel.scrollToCaret(ScrollType.CENTER)
                    }
                }
            }
        }
    }

    private fun createSourcePosition(stack: LuaPandaStack): XSourcePosition? {
        val filePath = stack.oPath ?: stack.file
        val lineNumber = stack.getLineNumber()
        
        if (lineNumber <= 0) return null
        
        val file = findSourceFile(filePath)
        
        return if (file != null) {
            XDebuggerUtil.getInstance().createPosition(file, lineNumber - 1)
        } else {
            logWithLevel("无法找到源文件: $filePath (oPath: ${stack.oPath}, file: ${stack.file})", LogLevel.DEBUG)
            null
        }
    }
    
    private fun findSourceFile(filePath: String): com.intellij.openapi.vfs.VirtualFile? {
        // 首先尝试使用LocalFileSystem查找文件（适用于绝对路径）
        var file = LocalFileSystem.getInstance().findFileByPath(filePath)
        
        // 如果LocalFileSystem找不到文件，尝试使用LuaFileUtil.findFile
        if (file == null) {
            file = LuaFileUtil.findFile(session.project, filePath)
        }
        
        // 如果还是找不到，尝试处理相对路径
        if (file == null && !filePath.startsWith("/") && !filePath.contains(":")) {
            val projectBasePath = session.project.basePath
            if (projectBasePath != null) {
                val absolutePath = "$projectBasePath/$filePath"
                file = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            }
        }
        
        // 如果仍然找不到，尝试只使用文件名在项目中搜索
        if (file == null) {
            val fileName = java.io.File(filePath).name
            file = LuaFileUtil.findFile(session.project, fileName.substringBeforeLast('.'))
        }
        
        return file
    }
    
    // ========== 工具方法 ==========
    
    /**
     * 根据日志级别控制日志输出
     */
    private fun logWithLevel(
        message: String, 
        level: LogLevel = LogLevel.CONNECTION,
        contentType: ConsoleViewContentType = ConsoleViewContentType.SYSTEM_OUTPUT
    ) {
        if (level.value >= configuration.logLevel) {
            println(message, LogConsoleType.NORMAL, contentType)
        }
    }

    /**
     * 发送需要响应的命令（统一处理回调）
     */
    private fun sendCommandWithResponse(command: String, data: Any?) {
        transporter?.commandToDebugger(command, data, { response ->
            handleCommandResponse(command, response)
        })
    }
    
    /**
     * 发送不需要响应的命令
     */
    private fun sendCommandNoResponse(command: String, data: Any? = null) {
        transporter?.commandToDebugger(command, data)
    }
    
    /**
     * 统一处理命令响应
     */
    private fun handleCommandResponse(command: String, response: LuaPandaMessage) {
        when (command) {
            LuaPandaCommands.INIT_SUCCESS -> {
                handleInitializationResponse(response)
            }
            LuaPandaCommands.STOP_RUN -> {
                logWithLevel("收到停止确认，关闭连接", LogLevel.CONNECTION)
                stopTransporter()
            }
            LuaPandaCommands.STEP_OVER,
            LuaPandaCommands.STEP_IN,
            LuaPandaCommands.STEP_OUT -> {
                logWithLevel("收到单步调试响应: $command", LogLevel.DEBUG)
                // 单步调试的响应通常会通过handleMessage中的断点消息处理
            }
            else -> {
                logWithLevel("收到未知命令响应: $command", LogLevel.DEBUG)
            }
        }
    }

    /**
     * 支持回调的sendMessage方法（保持向后兼容）
     */
    fun sendMessage(message: LuaPandaMessage, callback: (LuaPandaMessage?) -> Unit) {
        transporter?.sendMessage(message, callback)
    }
}
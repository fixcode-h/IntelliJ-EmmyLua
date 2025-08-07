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

class LuaPandaDebugProcess(session: XDebugSession) : LuaDebugProcess(session) {
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
    
    /**
     * 根据日志级别控制日志输出
     * @param message 日志消息
     * @param level 日志级别
     * @param contentType 内容类型
     */
    private fun logWithLevel(
        message: String, 
        level: LogLevel = LogLevel.CONNECTION,
        contentType: ConsoleViewContentType = ConsoleViewContentType.SYSTEM_OUTPUT
    ) {
        // 连接日志始终打印，或者日志级别大于等于配置的级别时才打印
        if (level.value >= configuration.logLevel) {
            println(message, LogConsoleType.NORMAL, contentType)
        }
    }
    
    override fun sessionInitialized() {
        super.sessionInitialized()
        logWithLevel("调试会话初始化完成", LogLevel.CONNECTION)
        ApplicationManager.getApplication().executeOnPooledThread {
            setupTransporter()
        }
    }

    private fun setupTransporter() {
        val transportInfo = when (configuration.transportType) {
            LuaPandaTransportType.TCP_CLIENT -> {
                "TCP客户端 ${configuration.host}:${configuration.port}"
            }
            LuaPandaTransportType.TCP_SERVER -> {
                "TCP服务器 端口:${configuration.port}"
            }
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
        
        // 设置传输器的日志级别
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
        
        try {
            transporter?.start()
        } catch (e: Exception) {
            logWithLevel("传输器启动失败: ${e.message}", LogLevel.CONNECTION, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            logger.error("Failed to start transporter", e)
            onDisconnect()
        }
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    private fun onConnect() {
        logWithLevel("连接建立，等待连接稳定后发送初始化消息...", LogLevel.CONNECTION)
        
        // 延迟发送初始化消息，确保连接稳定
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(1000) // 增加延迟到1000ms，给Lua端更多准备时间
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
    
    private fun sendInitializationMessageWithRetry(attempt: Int) {
        if (attempt > 3) {
            logWithLevel("初始化消息发送失败，已达到最大重试次数", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            return
        }
        
        logWithLevel("发送初始化消息 (尝试 $attempt/3)...", LogLevel.CONNECTION)
        
        try {
            sendInitializationMessage()
        } catch (e: Exception) {
            logWithLevel("初始化消息发送失败: ${e.javaClass.simpleName} - ${e.message}, 将在1秒后重试", LogLevel.DEBUG)
            
            // 如果是连接相关的异常，记录更详细的信息
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
            
            // 如果发送失败，延迟后重试
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
    }
    
    private fun sendInitializationMessage() {
        // 检查连接状态
        if (transporter == null || session.isStopped) {
            logWithLevel("传输器不可用或会话已停止，跳过初始化消息发送", LogLevel.DEBUG)
            return
        }
        
        // 额外验证连接状态，确保连接真正稳定
        try {
            // 给连接一个小的额外延迟，确保Lua端完全准备好
            Thread.sleep(200)
        } catch (e: InterruptedException) {
            logWithLevel("连接验证被中断", LogLevel.DEBUG)
            return
        }
        
        // 获取插件目录下的libpdebug库路径
        val pluginPath = try {
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
        
        // 发送初始化消息，包含完整的初始化参数（字段名与VSCode插件保持一致）
        val initInfo = LuaPandaInitInfo(
            stopOnEntry = configuration.stopOnEntry.toString(),
            useCHook = configuration.useCHook.toString(),
            logLevel = configuration.logLevel.toString(),
            luaFileExtension = "lua",
            cwd = session.project.basePath ?: System.getProperty("user.dir"),
            isNeedB64EncodeStr = "true",
            TempFilePath = session.project.basePath ?: System.getProperty("user.dir"),
            pathCaseSensitivity = "true",
            OSType = System.getProperty("os.name"),  // 修正字段名
            clibPath = pluginPath,
            adapterVersion = "1.0.0",
            autoPathMode = "false",
            distinguishSameNameFile = "false",
            truncatedOPath = "",
            DevelopmentMode = "false"  // 修正字段名
        )
        
        logWithLevel("发送初始化消息...", LogLevel.CONNECTION)
        
        try {
            // 使用commandToDebugger发送initSuccess消息并处理回调
            transporter?.commandToDebugger(LuaPandaCommands.INIT_SUCCESS, initInfo, { response ->
                // 处理initSuccess的回调响应
                response.getInfoAsObject()?.let { info ->
                    val useHookLib = info.get("UseHookLib")?.asString ?: "0"
                    val useLoadstring = info.get("UseLoadstring")?.asString ?: "0"
                    val isNeedB64EncodeStr = info.get("isNeedB64EncodeStr")?.asString ?: "false"
                    
                    logWithLevel("初始化完成 - HookLib:$useHookLib, Loadstring:$useLoadstring, B64:$isNeedB64EncodeStr", LogLevel.DEBUG)
                    logger.info("LuaPanda initialized - UseHookLib: $useHookLib, UseLoadstring: $useLoadstring, B64Encode: $isNeedB64EncodeStr")
                    
                    // 标记初始化完成
                    isInitialized = true
                    
                    // 初始化成功后再发送现有断点
                    val breakpoints = XDebuggerManager.getInstance(session.project)
                        .breakpointManager
                        .getBreakpoints(LuaLineBreakpointType::class.java)
                    if (breakpoints.isNotEmpty()) {
                        logWithLevel("发送现有断点: ${breakpoints.size}个", LogLevel.DEBUG)
                        breakpoints.forEach { breakpoint ->
                            breakpoint.sourcePosition?.let { position ->
                                registerBreakpoint(position, breakpoint)
                            }
                        }
                    } else {
                        logWithLevel("没有现有断点需要发送", LogLevel.DEBUG)
                    }
                }
            })
            
            logWithLevel("初始化消息已发送，等待Lua端响应...", LogLevel.CONNECTION)
            
        } catch (e: Exception) {
            logWithLevel("发送初始化消息时出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            throw e // 重新抛出异常，让重试机制处理
        }
    }

    private fun onDisconnect() {
        logWithLevel("连接断开", LogLevel.CONNECTION)
        isInitialized = false
        
        ApplicationManager.getApplication().invokeLater {
            try {
                // 停止当前传输器
                transporter?.stop()
                transporter = null
                
                // 检查是否启用了自动重连
                if (configuration.autoReconnect && !session.isStopped) {
                    logWithLevel("自动重连已启用，重新启动传输器等待Lua程序重连...", LogLevel.CONNECTION)
                    
                    // 重新启动传输器
                    try {
                        startTransporter()
                        logWithLevel("传输器重启成功，等待Lua程序重连...", LogLevel.CONNECTION)
                    } catch (e: Exception) {
                        logWithLevel("重启传输器失败: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                        // 如果无法重启传输器，则停止调试会话
                        stopDebugSession()
                    }
                } else {
                    if (!configuration.autoReconnect) {
                        logWithLevel("自动重连已禁用，停止调试会话", LogLevel.CONNECTION)
                    } else {
                        logWithLevel("调试会话已停止，无需重连", LogLevel.CONNECTION)
                    }
                    stopDebugSession()
                }
            } catch (e: Exception) {
                logWithLevel("断开连接处理时出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                stopDebugSession()
            }
        }
    }
    
    private fun stopDebugSession() {
        ApplicationManager.getApplication().invokeLater {
            try {
                // 停止传输器
                transporter?.stop()
                transporter = null
                
                // 停止调试会话
                session?.stop()
                logWithLevel("调试会话已停止", LogLevel.CONNECTION)
            } catch (e: Exception) {
                logWithLevel("停止调试会话时出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                logger.error("Error stopping debug session", e)
            }
        }
    }

    private fun handleMessage(message: LuaPandaMessage) {
        when (message.cmd) {
            LuaPandaCommands.STOP_ON_BREAKPOINT, LuaPandaCommands.STOP_ON_ENTRY, LuaPandaCommands.STEP_OVER, LuaPandaCommands.STEP_IN, LuaPandaCommands.STEP_OUT -> {
                // 优先从stack字段获取堆栈信息（新格式），如果没有则从info字段获取（旧格式）
                val stacks = if (message.stack != null) {
                    message.stack
                } else if (message.getInfoAsObject() !null) {仍
运行              if (!transporter.isRunning()) {
                      Gson().fmes已停止运行ge.getInfoAsObject(), Array<LuaP    return falseandaStack>::cl}
            
            asst(连接状态
             if (!transsorter.esCon ected()) {
                logWithLevel("传输器显示未连接状态", Lo{Level.DEBUG)
                return false
            }          logWithLevel("警告：消息中没有找到堆栈信息", LogLevel.DEBUG, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                    emptyList()
                }
                
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
            LuaPandaCommands.STOP_RUN -> {
                // 处理Lua程序主动发送的停止运行命令
                logWithLevel("收到Lua程序停止运行命令", LogLevel.CONNECTION)
                ApplicationManager.getApplication().invokeLater {
                    try {
                        // 停止调试会话
                        session?.stop()
                        logWithLevel("调试会话已停止", LogLevel.CONNECTION)
                    } catch (e: Exception) {
                        logWithLevel("停止调试会话时出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                        logger.error("Error stopping debug session on STOP_RUN", e)
                    }
                }
            }
            LuaPandaCommands.OUTPUT -> {
                val outputText = message.getInfoAsObject()?.get("content")?.asString ?: ""
                logWithLevel(outputText, LogLevel.DEBUG)
            }
            else -> {
                logger.info("Unknown message: ${message.cmd}")
            }
        }
    }

    private fun createSourcePosition(stack: LuaPandaStack): XSourcePosition? {
        val filePath = stack.oPath ?: stack.file
        val lineNumber = stack.getLineNumber()
        
        if (lineNumber <= 0) return null
        
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
        
        return if (file != null) {
            XDebuggerUtil.getInstance().createPosition(file, lineNumber - 1) // Convert to 0-based
        } else {
            // 如果找不到文件，记录日志以便调试
            logWithLevel("无法找到源文件: $filePath (oPath: ${stack.oPath}, file: ${stack.file})", LogLevel.DEBUG)
            null
        }
    }

    private fun onBreak(stacks: List<LuaPandaStack>) {
        if (stacks.isNotEmpty()) {
            val suspendContext = LuaPandaSuspendContext(this, stacks)
            val topStack = stacks[0]
            
            ApplicationManager.getApplication().invokeLater {
                // 检查是否是断点命中 - 使用改进的文件查找逻辑
                val sourcePosition = createSourcePosition(topStack)
                
                // 设置顶部帧，确保堆栈窗口正确选中当前执行位置
                val executionStack = suspendContext.activeExecutionStack
                if (executionStack is LuaPandaExecutionStack && sourcePosition != null) {
                    val topFrame = executionStack.topFrame
                    if (topFrame != null) {
                        executionStack.setTopFrame(topFrame)
                    }
                }
                
                val breakpoint = sourcePosition?.let { position ->
                    val breakpointManager = XDebuggerManager.getInstance(session.project).breakpointManager
                    val breakpoints = breakpointManager.getBreakpoints(LuaLineBreakpointType::class.java)
                    breakpoints.find { bp ->
                        bp.sourcePosition?.file == position.file && bp.sourcePosition?.line == position.line
                    }
                }
                
                if (breakpoint != null) {
                    // 断点命中
                    session.breakpointReached(breakpoint, null, suspendContext)
                } else {
                    // 单步调试或其他情况
                    session.positionReached(suspendContext)
                }
                
                // 确保执行点高亮显示
                session.showExecutionPoint()
                
                // 强制刷新编辑器以确保高亮显示
                if (sourcePosition != null) {
                    ApplicationManager.getApplication().invokeLater {
                        val fileEditorManager = FileEditorManager.getInstance(session.project)
                        val editor = fileEditorManager.openFile(sourcePosition.file, true, true)
                        if (editor.isNotEmpty()) {
                            val textEditor = editor[0] as? TextEditor
                            textEditor?.editor?.let { editorInstance ->
                                // 确保光标移动到正确位置
                                val offset = editorInstance.document.getLineStartOffset(sourcePosition.line)
                                editorInstance.caretModel.moveToOffset(offset)
                                // 滚动到可见区域
                                editorInstance.scrollingModel.scrollToCaret(ScrollType.CENTER)
                            }
                        }
                    }
                }
            }
        } else {
            logger.warn("Received empty stack frames")
        }
    }

    override fun registerBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val filePath = sourcePosition.file.canonicalPath ?: sourcePosition.file.path
        
        val newId = idCounter++
        breakpoint.putUserData(ID, newId)
        
        logWithLevel("设置断点: ${sourcePosition.file.name}:${breakpoint.line + 1}", LogLevel.DEBUG)
        
        val breakpointInfo = BreakpointInfo(
            verified = true,
            type = 2,
            line = breakpoint.line + 1 // Convert to 1-based
        )
        
        val luaPandaBreakpoint = LuaPandaBreakpoint(
            path = filePath,
            bks = listOf(breakpointInfo)
        )
        
        breakpoints[newId] = luaPandaBreakpoint
        
        // 只有在初始化完成后才发送断点
        if (isInitialized) {
            logWithLevel("发送断点到调试器: ${sourcePosition.file.name}:${breakpoint.line + 1}", LogLevel.DEBUG)
            transporter?.commandToDebugger(LuaPandaCommands.SET_BREAKPOINT, luaPandaBreakpoint)
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
            // 发送空的断点列表来移除断点
            val emptyBreakpoint = LuaPandaBreakpoint(
                path = luaPandaBreakpoint.path,
                bks = emptyList()
            )
            // 断点移除是状态通知协议，不需要回调
            transporter?.commandToDebugger(LuaPandaCommands.SET_BREAKPOINT, emptyBreakpoint)
        }
    }

    override fun startPausing() {
        // 暂停是状态通知协议，不需要回调
        transporter?.commandToDebugger(LuaPandaCommands.STOP_ON_BREAKPOINT)
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
        // 运行到指定位置是状态通知协议，不需要回调
        transporter?.commandToDebugger(LuaPandaCommands.SET_BREAKPOINT, tempBreakpoint)
    }

    override fun run() {
        logWithLevel("继续运行", LogLevel.DEBUG)
        // 继续运行是状态通知协议，不需要回调
        transporter?.commandToDebugger(LuaPandaCommands.CONTINUE)
    }

    override fun stop() {
        logWithLevel("停止调试", LogLevel.DEBUG)
        
        // 发送STOP_RUN命令并等待Lua端回调确认
        if (transporter != null) {
            try {
                // 标记停止确认状态
                val stopConfirmed = AtomicBoolean(false)
                
                // 使用带回调的commandToDebugger，等待Lua端确认
                transporter?.commandToDebugger(LuaPandaCommands.STOP_RUN, null, { response ->
                    logWithLevel("收到停止确认，关闭连接", LogLevel.CONNECTION)
                    stopConfirmed.set(true)
                    // 收到Lua端回调后再停止传输器并清理资源
                    transporter?.stop()
                    transporter = null
                })
                
                // 设置超时机制，如果指定时间内没有收到回调，强制停止
                Thread {
                    try {
                        val timeoutMs = (configuration.stopConfirmTimeout * 1000).toLong()
                        logWithLevel("等待停止确认，超时时间: ${configuration.stopConfirmTimeout}秒", LogLevel.CONNECTION)
                        Thread.sleep(timeoutMs)
                        
                        // 检查是否已经收到确认
                        if (!stopConfirmed.get() && transporter != null) {
                            logWithLevel("停止确认超时，强制关闭连接", LogLevel.CONNECTION)
                            transporter?.stop()
                            transporter = null
                        }
                    } catch (e: InterruptedException) {
                        logWithLevel("停止确认等待被中断", LogLevel.DEBUG)
                        Thread.currentThread().interrupt()
                    }
                }.start()
                
            } catch (e: Exception) {
                logWithLevel("发送停止命令时出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                e.printStackTrace()
                // 发送失败时直接停止
                transporter?.stop()
                transporter = null
            }
        } else {
            logWithLevel("transporter为null，无法发送stopRun命令", LogLevel.DEBUG)
        }
    }

    override fun startStepOver(context: XSuspendContext?) {
        logWithLevel("单步跳过", LogLevel.DEBUG)
        // 单步跳过是响应协议，需要回调
        transporter?.commandToDebugger(LuaPandaCommands.STEP_OVER, null, { response ->
            // 处理单步跳过的回调响应
        })
    }

    override fun startStepInto(context: XSuspendContext?) {
        logWithLevel("单步进入", LogLevel.DEBUG)
        // 单步进入是响应协议，需要回调
        transporter?.commandToDebugger(LuaPandaCommands.STEP_IN, null, { response ->
            // 处理单步进入的回调响应
        })
    }

    override fun startStepOut(context: XSuspendContext?) {
        logWithLevel("单步跳出", LogLevel.DEBUG)
        // 单步跳出是响应协议，需要回调
        transporter?.commandToDebugger(LuaPandaCommands.STEP_OUT, null, { response ->
            // 处理单步跳出的回调响应
        })
    }

    // 支持回调的sendMessage方法
    fun sendMessage(message: LuaPandaMessage, callback: ((LuaPandaMessage?) -> Unit)) {
        transporter?.sendMessage(message, callback)
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
}
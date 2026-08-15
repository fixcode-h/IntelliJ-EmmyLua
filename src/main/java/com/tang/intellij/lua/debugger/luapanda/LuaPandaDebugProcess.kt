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
import com.tang.intellij.lua.debugger.core.DebugSessionEvent
import com.tang.intellij.lua.debugger.core.DebugSessionEventLoop
import com.tang.intellij.lua.debugger.core.DebugSessionState
import com.tang.intellij.lua.psi.LuaFileUtil
import com.tang.intellij.lua.LuaBundle
import com.google.gson.Gson

/**
 * LuaPanda调试进程
 * 负责管理调试会话、传输器连接、断点管理和调试控制
 */
class LuaPandaDebugProcess(session: XDebugSession) : LuaDebugProcess(session) {
    
    // ========== 属性定义 ==========
    
    private val configuration = session.runProfile as LuaPandaDebugConfiguration
    private val editorsProvider = LuaPandaDebuggerEditorsProvider()
    private val breakpoints = mutableMapOf<Int, LuaPandaBreakpoint>()
    private var idCounter = 0
    internal var transporter: LuaPandaTransporter? = null
    private val logger = Logger.getInstance(LuaPandaDebugProcess::class.java)
    private val lifecycle = DebugSessionEventLoop(
        threadName = "LuaPanda-Session",
        transitionListener = { transition ->
            logger.debug("LuaPanda session: ${transition.previous} --${transition.event}--> ${transition.current}")
        },
        errorHandler = { error -> logger.error("LuaPanda session event failed", error) }
    )
    @Volatile private var sessionGeneration = 0L
    private val isInitialized: Boolean get() = lifecycle.state == DebugSessionState.RUNNING
    private val isStopping: Boolean get() = lifecycle.state == DebugSessionState.STOPPING
    private val isClientMode = configuration.transportType == LuaPandaTransportType.TCP_CLIENT

    companion object {
        private val ID = Key.create<Int>("luapanda.breakpoint")
    }
    
    // ========== 生命周期管理 ==========
    
    override fun sessionInitialized() {
        super.sessionInitialized()
        logWithLevel("调试会话初始化完成", LogLevel.CONNECTION)
        sessionGeneration = lifecycle.start()
        lifecycle.post(sessionGeneration, DebugSessionEvent.TARGET_READY) {
            setupTransporter()
        }
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    override fun stop() {
        clearInlineSnapshot()
        val notifyTarget = isInitialized
        lifecycle.post(sessionGeneration, DebugSessionEvent.STOP_REQUESTED) {
            performStop(notifyTarget)
        }
    }

    private fun performStop(notifyTarget: Boolean) {
        logWithLevel("🛑 调试会话停止中...", LogLevel.CONNECTION)
        try {
            // 1. 先停止连接尝试和重连机制
            transporter?.let { 
                if (it is LuaPandaTcpClientTransporter) {
                    it.stopReconnectAttempts()
                }
            }
            
            // 2. 如果调试器已初始化，发送停止命令
            if (notifyTarget && transporter != null) {
                try {
                    logWithLevel("📤 发送停止运行命令到Lua调试器", LogLevel.DEBUG)
                    
                    // 参照VSCode插件的disconnectRequest，给Lua发消息让其停止运行
                    transporter?.commandToDebugger(LuaPandaCommands.STOP_RUN, null, { response ->
                        logWithLevel("✅ 收到Lua端停止确认", LogLevel.CONNECTION)
                        finalizeStop()
                    }, configuration.stopConfirmTimeout, { finalizeStop() })
                    
                } catch (e: Exception) {
                    logWithLevel("❌ 发送停止命令失败: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                    finalizeStop() // 即使发送失败也要清理资源
                }
            } else {
                logWithLevel("⚠️ 调试器未初始化或传输器不可用，直接清理资源", LogLevel.DEBUG)
                finalizeStop()
            }
            
        } catch (e: Exception) {
            logWithLevel("❌ 停止过程中出现异常: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            e.printStackTrace()
            finalizeStop() // 确保资源得到清理
        }
    }
    
    /**
     * 最终停止处理，清理所有资源
     * 参照VSCode插件的资源清理逻辑
     */
    private fun finalizeStop() {
        lifecycle.post(sessionGeneration, DebugSessionEvent.TERMINATED) {
            cleanupResources()
        }
    }

    private fun cleanupResources() {
        try {
            clearInlineSnapshot()
            logWithLevel("🧹 清理调试会话资源...", LogLevel.DEBUG)
            
            // 1. 停止并清理传输器
            transporter?.let { 
                try {
                    it.stop()
                    logWithLevel("✅ 传输器已停止", LogLevel.DEBUG)
                } catch (e: Exception) {
                    logWithLevel("⚠️ 停止传输器时出错: ${e.message}", LogLevel.DEBUG)
                }
            }
            
            // 2. 清理回调和状态
            transporter?.clearCallbacks()
            
            // 3. 释放传输器引用
            transporter = null
            
            // 4. 清理断点映射
            breakpoints.clear()
            
            logWithLevel("✅ 调试会话资源清理完成", LogLevel.CONNECTION)
            
        } catch (e: Exception) {
            logWithLevel("❌ 资源清理过程中出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
        } finally {
            lifecycle.close()
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
                LuaPandaTcpServerTransporter(configuration.port, configuration.autoReconnect, this)
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
        transporter?.setEventDispatcher { action ->
            lifecycle.execute(sessionGeneration, action)
        }
        
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
    
    // ========== 连接管理 ==========
    
    private fun onConnect() {
        lifecycle.post(sessionGeneration, DebugSessionEvent.CONNECTED) {
            logWithLevel("连接建立，立即发送初始化消息...", LogLevel.DEBUG)
            if (transporter != null && !session.isStopped) {
                sendInitializationMessageWithRetry(1)
            }
        }
    }

    /**
     * 断开连接处理
     * 参照VSCode插件的onDisconnect逻辑
     */
    private fun onDisconnect() {
        logWithLevel("📡 连接断开", LogLevel.CONNECTION)
        
        if (isStopping) {
            logWithLevel("🏁 调试会话已正常结束", LogLevel.CONNECTION)
            finalizeStop()
        } else {
            // 非主动停止的断开
            if (configuration.autoReconnect && !session.isStopped) {
                lifecycle.post(sessionGeneration, DebugSessionEvent.RECONNECTING) {
                    logWithLevel("🔄 客户端连接断开，等待重新连接...", LogLevel.CONNECTION)
                    logWithLevel("🔄 自动重连已启用，正在尝试重新连接...", LogLevel.CONNECTION)
                }
            } else {
                // 没有启用自动重连或会话已停止，应该停止调试会话
                logWithLevel("❌ 客户端连接断开，自动重连已禁用", LogLevel.CONNECTION)
                logWithLevel("🛑 正在停止调试会话...", LogLevel.CONNECTION)
                
                // 在UI线程中停止调试会话
                lifecycle.post(sessionGeneration, DebugSessionEvent.FAILED) {
                    transporter?.stop()
                    transporter?.clearCallbacks()
                    transporter = null
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            session.stop()
                            logWithLevel("✅ 调试会话已停止", LogLevel.CONNECTION)
                        } catch (e: Exception) {
                            logWithLevel("❌ 停止调试会话时出错: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                            logger.error("Error stopping debug session on disconnect", e)
                        } finally {
                            lifecycle.close()
                        }
                    }
                }
            }
        }
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
            // 将LuaPandaInitInfo转换为Map，作为info字段的内容
            val initInfoMap = mapOf(
                "stopOnEntry" to initInfo.stopOnEntry,
                "luaFileExtension" to initInfo.luaFileExtension,
                "cwd" to initInfo.cwd,
                "isNeedB64EncodeStr" to initInfo.isNeedB64EncodeStr,
                "TempFilePath" to initInfo.TempFilePath,
                "logLevel" to initInfo.logLevel,
                "pathCaseSensitivity" to initInfo.pathCaseSensitivity,
                "OSType" to initInfo.OSType,
                "clibPath" to initInfo.clibPath,
                "useCHook" to initInfo.useCHook,
                "adapterVersion" to initInfo.adapterVersion,
                "autoPathMode" to initInfo.autoPathMode,
                "distinguishSameNameFile" to initInfo.distinguishSameNameFile,
                "truncatedOPath" to initInfo.truncatedOPath,
                "DevelopmentMode" to initInfo.DevelopmentMode
            )
            
            // 参照VSCode插件，直接发送命令而不是通过sendCommandWithResponse
            transporter?.commandToDebugger(LuaPandaCommands.INIT_SUCCESS, initInfoMap, { response ->
                handleInitializationResponse(response)
            }, 0)
            
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
    
    /**
     * 创建初始化信息
     * 参照VSCode插件的实现，提供完整的配置参数
     */
    private fun createInitInfo(pluginPath: String): LuaPandaInitInfo {
        val osType = System.getProperty("os.name")
        val projectPath = session.project.basePath ?: System.getProperty("user.dir")
        
        return LuaPandaInitInfo(
            stopOnEntry = configuration.stopOnEntry.toString(),
            luaFileExtension = configuration.luaFileExtension.ifEmpty { "lua" },
            cwd = projectPath,
            isNeedB64EncodeStr = "true", // 固定使用Base64编码，避免字符串中的特殊字符问题
            TempFilePath = configuration.tempFilePath.ifEmpty { projectPath },
            logLevel = configuration.logLevel.toString(),
            pathCaseSensitivity = "true", // Windows/Linux路径大小写敏感性
            OSType = osType,
            clibPath = pluginPath,
            useCHook = configuration.useCHook.toString(),
            adapterVersion = "1.0.0", // 插件版本信息
            autoPathMode = configuration.autoPathMode.toString(),
            distinguishSameNameFile = configuration.distinguishSameNameFile.toString(),
            truncatedOPath = configuration.truncatedOPath,
            DevelopmentMode = configuration.developmentMode.toString()
        )
    }
    
    /**
     * 处理初始化响应并进行后续设置
     * 参照VSCode插件的实现，完整处理调试器状态
     */
    private fun handleInitializationResponse(response: LuaPandaMessage) {
        logWithLevel("🎯 收到Lua端初始化响应，解析调试器状态...", LogLevel.CONNECTION)
        
        try {
            response.getInfoAsObject()?.let { info ->
                // 提取调试器状态信息
                val useHookLib = info.get("UseHookLib")?.asString ?: "0"
                val useLoadstring = info.get("UseLoadstring")?.asString ?: "0"
                val isNeedB64EncodeStr = info.get("isNeedB64EncodeStr")?.asString ?: "true"
                
                logWithLevel("📋 调试器配置 - Hook库:${if(useHookLib == "1") "启用" else "禁用"}, " +
                    "Loadstring:${if(useLoadstring == "1") "启用" else "禁用"}, " +
                    "Base64编码:${if(isNeedB64EncodeStr == "true") "启用" else "禁用"}", LogLevel.DEBUG)
                
                // 更新传输器的编码设置
                transporter?.enableB64Encoding(isNeedB64EncodeStr == "true")
                
                logger.info("LuaPanda初始化成功 - UseHookLib: $useHookLib, UseLoadstring: $useLoadstring, B64Encode: $isNeedB64EncodeStr")
                
                markInitialized()
                
            } ?: run {
                logWithLevel("⚠️ 初始化响应缺少调试器状态信息，使用默认设置", LogLevel.DEBUG)
                markInitialized()
            }
            
        } catch (e: Exception) {
            logWithLevel("❌ 解析初始化响应失败: ${e.message}", LogLevel.ERROR, contentType = ConsoleViewContentType.ERROR_OUTPUT)
            logger.error("Failed to parse initialization response", e)
            
            // 即使解析失败，也标记为已初始化，允许基本调试功能
            markInitialized()
        }
    }

    private fun markInitialized() {
        lifecycle.post(sessionGeneration, DebugSessionEvent.INITIALIZED) {
            sendExistingBreakpoints()
            logWithLevel("🚀 调试器现已就绪，可以开始调试", LogLevel.CONNECTION)
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
    
    /**
     * 处理来自调试器的消息
     * 参照VSCode插件dataProcessor.getData中的消息处理逻辑
     */
    private fun handleMessage(message: LuaPandaMessage) {
        logWithLevel("收到消息: ${message.cmd}", LogLevel.DEBUG)
        
        when (message.cmd) {
            // ========== 断点相关消息 ==========
            LuaPandaCommands.STOP_ON_BREAKPOINT -> {
                logWithLevel("🎯 断点命中", LogLevel.DEBUG)
                handleBreakMessage(message)
            }
            LuaPandaCommands.STOP_ON_ENTRY -> {
                logWithLevel("🏁 程序入口暂停", LogLevel.DEBUG)
                handleBreakMessage(message)
            }
            LuaPandaCommands.STOP_ON_CODE_BREAKPOINT -> {
                logWithLevel("💻 代码断点命中", LogLevel.DEBUG)
                handleBreakMessage(message)
            }
            
            // ========== 单步调试相关消息 ==========
            LuaPandaCommands.STEP_OVER -> {
                logWithLevel("👣 单步跳过完成", LogLevel.DEBUG)
                handleBreakMessage(message)
            }
            LuaPandaCommands.STEP_IN -> {
                logWithLevel("📥 单步进入完成", LogLevel.DEBUG)
                handleBreakMessage(message)
            }
            LuaPandaCommands.STEP_OUT -> {
                logWithLevel("📤 单步跳出完成", LogLevel.DEBUG)
                handleBreakMessage(message)
            }
            
            // ========== 程序控制相关消息 ==========
            LuaPandaCommands.STOP_RUN -> {
                logWithLevel("🛑 程序停止运行", LogLevel.CONNECTION)
                handleStopRunMessage()
            }
            LuaPandaCommands.CONTINUE -> {
                logWithLevel("▶️ 程序继续运行", LogLevel.DEBUG)
                // 继续运行通常不需要特殊处理，只是确认消息
            }
            
            // ========== 输出和日志相关消息 ==========
            "output" -> {
                handleOutputMessage(message)
            }
            "debug_console" -> {
                handleDebugConsoleMessage(message)
            }
            
            // ========== 内存和状态相关消息 ==========
            "refreshLuaMemory" -> {
                handleMemoryRefreshMessage(message)
            }
            "tip" -> {
                handleTipMessage(message)
            }
            "tipError" -> {
                handleTipErrorMessage(message)
            }
            
            // ========== 未知消息 ==========
            else -> {
                logWithLevel("⚠️ 收到未知消息类型: ${message.cmd}", LogLevel.DEBUG, contentType = ConsoleViewContentType.ERROR_OUTPUT)
                logger.info("Unknown message received: ${message.cmd}")
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
        val messageStacks = message.stack
        return if (messageStacks != null) {
            messageStacks
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
    
    /**
     * 处理输出消息
     */
    private fun handleOutputMessage(message: LuaPandaMessage) {
        val info = message.getInfoAsObject()
        val logInfo = info?.get("logInfo")?.asString ?: ""
        if (logInfo.isNotEmpty()) {
            logWithLevel("📤 [Lua输出] $logInfo", LogLevel.DEBUG)
        }
    }
    
    /**
     * 处理调试控制台消息
     */
    private fun handleDebugConsoleMessage(message: LuaPandaMessage) {
        val info = message.getInfoAsObject()
        val logInfo = info?.get("logInfo")?.asString ?: ""
        if (logInfo.isNotEmpty()) {
            logWithLevel("[调试控制台] $logInfo", LogLevel.DEBUG, contentType = ConsoleViewContentType.SYSTEM_OUTPUT)
        }
    }
    
    /**
     * 处理内存刷新消息
     */
    private fun handleMemoryRefreshMessage(message: LuaPandaMessage) {
        val info = message.getInfoAsObject()
        val memInfo = info?.get("memInfo")?.asString ?: ""
        if (memInfo.isNotEmpty()) {
            logWithLevel("💾 Lua内存使用: ${memInfo}KB", LogLevel.DEBUG)
        }
    }
    
    /**
     * 处理提示消息
     */
    private fun handleTipMessage(message: LuaPandaMessage) {
        val info = message.getInfoAsObject()
        val logInfo = info?.get("logInfo")?.asString ?: ""
        if (logInfo.isNotEmpty()) {
            logWithLevel("💡 [提示] $logInfo", LogLevel.CONNECTION)
        }
    }
    
    /**
     * 处理错误提示消息
     */
    private fun handleTipErrorMessage(message: LuaPandaMessage) {
        val info = message.getInfoAsObject()
        val logInfo = info?.get("logInfo")?.asString ?: ""
        if (logInfo.isNotEmpty()) {
            logWithLevel("❌ [错误] $logInfo", LogLevel.CONNECTION, contentType = ConsoleViewContentType.ERROR_OUTPUT)
        }
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
        clearInlineSnapshot()
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
        clearInlineSnapshot()
        logWithLevel("继续运行", LogLevel.DEBUG)
        sendCommandNoResponse(LuaPandaCommands.CONTINUE)
    }

    override fun startStepOver(context: XSuspendContext?) {
        clearInlineSnapshot()
        logWithLevel("单步跳过", LogLevel.DEBUG)
        sendCommandWithResponse(LuaPandaCommands.STEP_OVER, null)
    }

    override fun startStepInto(context: XSuspendContext?) {
        clearInlineSnapshot()
        logWithLevel("单步进入", LogLevel.DEBUG)
        sendCommandWithResponse(LuaPandaCommands.STEP_IN, null)
    }

    override fun startStepOut(context: XSuspendContext?) {
        clearInlineSnapshot()
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
                if (sourcePosition != null) {
                    InlineDebugSnapshotStore.getInstance(session.project).update(
                        session,
                        topStack.toInlineSnapshot(
                            sourcePosition.file.canonicalPath ?: sourcePosition.file.path,
                            sourcePosition.line
                        )
                    )
                } else {
                    clearInlineSnapshot()
                }
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

    private fun clearInlineSnapshot() {
        InlineDebugSnapshotStore.getInstance(session.project).clear(session)
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
    internal fun logWithLevel(
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
        }, 0)
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
                finalizeStop()
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

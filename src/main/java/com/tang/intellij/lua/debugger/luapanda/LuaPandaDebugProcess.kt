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
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Key
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.tang.intellij.lua.debugger.*
import com.google.gson.Gson

class LuaPandaDebugProcess(session: XDebugSession) : LuaDebugProcess(session) {
    private val configuration = session.runProfile as LuaPandaDebugConfiguration
    private val editorsProvider = LuaDebuggerEditorsProvider()
    private val breakpoints = mutableMapOf<Int, LuaPandaBreakpoint>()
    private var idCounter = 0
    internal var transporter: LuaPandaTransporter? = null
    private val logger = Logger.getInstance(LuaPandaDebugProcess::class.java)

    companion object {
        private val ID = Key.create<Int>("luapanda.breakpoint")
    }
    
    override fun sessionInitialized() {
        super.sessionInitialized()
        println("[LuaPanda] 调试会话初始化完成", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        ApplicationManager.getApplication().executeOnPooledThread {
            setupTransporter()
        }
    }

    private fun setupTransporter() {
        println("[LuaPanda] 开始设置传输器，传输类型: ${configuration.transportType}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        transporter = when (configuration.transportType) {
            LuaPandaTransportType.TCP_CLIENT -> {
                println("[LuaPanda] 创建TCP客户端传输器，目标: ${configuration.host}:${configuration.port}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                LuaPandaTcpClientTransporter(configuration.host, configuration.port, this)
            }
            LuaPandaTransportType.TCP_SERVER -> {
                println("[LuaPanda] 创建TCP服务器传输器，监听端口: ${configuration.port}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                LuaPandaTcpServerTransporter(configuration.port, this)
            }
        }
        
        transporter?.setMessageHandler { message ->
            println("[LuaPanda] 收到调试器消息，开始处理", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            handleMessage(message)
        }
        
        transporter?.setConnectionHandler { connected ->
            if (connected) {
                println("[LuaPanda] 调试器连接状态变更: 已连接", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                onConnect()
            } else {
                println("[LuaPanda] 调试器连接状态变更: 已断开", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                onDisconnect()
            }
        }
        
        try {
            println("[LuaPanda] 启动传输器", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            transporter?.start()
        } catch (e: Exception) {
            println("[LuaPanda] 传输器启动失败: ${e.message}", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
            logger.error("Failed to start transporter", e)
            onDisconnect()
        }
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    private fun onConnect() {
        println("[LuaPanda] 开始初始化调试器连接", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        
        // 获取插件目录下的libpdebug库路径
        val pluginPath = try {
            println("[LuaPanda] 尝试获取插件路径...", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            val pluginDescriptor = PluginManagerCore.getPlugin(
                PluginId.getId("com.tang")
            )
            println("[LuaPanda] 插件描述符: $pluginDescriptor", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            
            if (pluginDescriptor != null) {
                val pluginBasePath = pluginDescriptor.pluginPath
                println("[LuaPanda] 插件基础路径: $pluginBasePath", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                
                val debuggerLibPath = pluginBasePath?.resolve("Debugger")?.resolve("luapanda")
                println("[LuaPanda] 调试器库路径: $debuggerLibPath", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                
                debuggerLibPath?.toString()?.let { it + java.io.File.separator } ?: ""
            } else {
                println("[LuaPanda] 插件描述符为null", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
                ""
            }
        } catch (e: Exception) {
            println("[LuaPanda] 获取插件路径失败: ${e.message}", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
            e.printStackTrace()
            ""
        }
        
        println("[LuaPanda] 设置clibPath为: $pluginPath", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        
        // 发送初始化消息，包含完整的初始化参数
        val initInfo = LuaPandaInitInfo(
            stopOnEntry = configuration.stopOnEntry.toString(),
            useCHook = configuration.useCHook.toString(),
            logLevel = configuration.logLevel.toString(),
            luaFileExtension = "lua",
            cwd = session.project.basePath ?: System.getProperty("user.dir"),
            isNeedB64EncodeStr = "false",
            TempFilePath = session.project.basePath ?: System.getProperty("user.dir"),
            pathCaseSensitivity = "true",
            osType = System.getProperty("os.name"),
            clibPath = pluginPath,
            adapterVersion = "1.0.0",
            autoPathMode = "false",
            distinguishSameNameFile = "false",
            truncatedOPath = "",
            developmentMode = "false"
        )
        
        val initMessage = LuaPandaMessage(LuaPandaCommands.INIT_SUCCESS, Gson().toJsonTree(initInfo).asJsonObject, "0")
        println("[LuaPanda] 发送初始化消息: ${Gson().toJson(initMessage)}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        
        // 发送initSuccess消息并处理回调
        transporter?.sendMessage(initMessage) { response ->
            println("[LuaPanda] 收到初始化响应", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            // 处理initSuccess的回调响应
            response?.info?.let { info ->
                val useHookLib = info.get("UseHookLib")?.asString ?: "0"
                val useLoadstring = info.get("UseLoadstring")?.asString ?: "0"
                val isNeedB64EncodeStr = info.get("isNeedB64EncodeStr")?.asString ?: "false"
                
                println("[LuaPanda] 初始化参数 - UseHookLib: $useHookLib, UseLoadstring: $useLoadstring, B64Encode: $isNeedB64EncodeStr", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                logger.info("LuaPanda initialized - UseHookLib: $useHookLib, UseLoadstring: $useLoadstring, B64Encode: $isNeedB64EncodeStr")
            }
            
            // 发送现有断点
            val breakpoints = XDebuggerManager.getInstance(session.project)
                .breakpointManager
                .getBreakpoints(LuaLineBreakpointType::class.java)
            println("[LuaPanda] 发送现有断点，数量: ${breakpoints.size}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            breakpoints.forEach { breakpoint ->
                breakpoint.sourcePosition?.let { position ->
                    registerBreakpoint(position, breakpoint)
                }
            }
        }
    }

    private fun onDisconnect() {
        println("[LuaPanda] 调试器连接断开，停止调试会话", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        stop()
        session?.stop()
    }

    private fun handleMessage(message: LuaPandaMessage) {
        println("[LuaPanda] 处理调试器消息，命令: ${message.cmd}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        when (message.cmd) {
            LuaPandaCommands.STOP_ON_BREAKPOINT -> {
                println("[LuaPanda] 处理断点停止消息", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                val stacks = Gson().fromJson(message.info, Array<LuaPandaStack>::class.java).toList()
                println("[LuaPanda] 解析到 ${stacks.size} 个堆栈帧", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                onBreak(stacks)
            }
            LuaPandaCommands.OUTPUT -> {
                val outputText = message.info?.get("content")?.asString ?: ""
                println("[LuaPanda] 处理输出消息: $outputText", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                println(outputText, LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            }
            else -> {
                println("[LuaPanda] 处理未知消息类型: ${message.cmd}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                logger.info("Unknown message: ${message.cmd}")
            }
        }
    }

    private fun onBreak(stacks: List<LuaPandaStack>) {
        println("[LuaPanda] 程序在断点处暂停，堆栈帧数量: ${stacks.size}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        
        if (stacks.isNotEmpty()) {
            val topStack = stacks[0]
            println("[LuaPanda] 当前执行位置 - 文件: ${topStack.file}, 行号: ${topStack.line}, 函数: ${topStack.functionName}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            
            println("[LuaPanda] 创建暂停上下文并准备暂停调试会话", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            val suspendContext = LuaPandaSuspendContext(this, stacks)
            ApplicationManager.getApplication().invokeLater {
                println("[LuaPanda] 调试会话已暂停在断点位置", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                session.positionReached(suspendContext)
                session.showExecutionPoint()
            }
        } else {
            println("[LuaPanda] 警告：收到空的堆栈帧列表", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
            logger.warn("Received empty stack frames")
        }
    }

    override fun registerBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val filePath = sourcePosition.file.canonicalPath ?: sourcePosition.file.path
        
        val newId = idCounter++
        breakpoint.putUserData(ID, newId)
        
        println("[LuaPanda] 注册断点 - 文件: $filePath, 行号: ${breakpoint.line + 1}, ID: $newId", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        
        val breakpointInfo = BreakpointInfo(
            line = breakpoint.line + 1, // Convert to 1-based
            condition = breakpoint.conditionExpression?.expression,
            logMessage = if (breakpoint.isLogMessage) breakpoint.logExpressionObject?.expression else null
        )
        
        val luaPandaBreakpoint = LuaPandaBreakpoint(
            path = filePath,
            bks = listOf(breakpointInfo)
        )
        
        breakpoints[newId] = luaPandaBreakpoint
        
        val message = LuaPandaMessage(LuaPandaCommands.SET_BREAKPOINT, Gson().toJsonTree(luaPandaBreakpoint).asJsonObject, "0")
        println("[LuaPanda] 发送设置断点消息: ${Gson().toJson(message)}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        transporter?.sendMessage(message)
    }

    override fun unregisterBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val id = breakpoint.getUserData(ID)
        val luaPandaBreakpoint = breakpoints[id]
        if (luaPandaBreakpoint != null) {
            println("[LuaPanda] 取消断点 - 文件: ${luaPandaBreakpoint.path}, ID: $id", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            breakpoints.remove(id)
            // 发送空的断点列表来移除断点
            val emptyBreakpoint = LuaPandaBreakpoint(
                path = luaPandaBreakpoint.path,
                bks = emptyList()
            )
            val message = LuaPandaMessage(LuaPandaCommands.SET_BREAKPOINT, Gson().toJsonTree(emptyBreakpoint).asJsonObject, "0")
            println("[LuaPanda] 发送移除断点消息: ${Gson().toJson(message)}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            transporter?.sendMessage(message)
        }
    }

    override fun startPausing() {
        val message = LuaPandaMessage(LuaPandaCommands.STOP_ON_BREAKPOINT, null, "0")
        transporter?.sendMessage(message)
    }

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        val filePath = position.file.canonicalPath ?: position.file.path
        val tempBreakpointInfo = BreakpointInfo(
            line = position.line + 1,
            condition = null,
            logMessage = null
        )
        val tempBreakpoint = LuaPandaBreakpoint(
            path = filePath,
            bks = listOf(tempBreakpointInfo)
        )
        val message = LuaPandaMessage(LuaPandaCommands.SET_BREAKPOINT, Gson().toJsonTree(tempBreakpoint).asJsonObject, "0")
        transporter?.sendMessage(message)
    }

    override fun run() {
        println("[LuaPanda] 执行继续运行命令", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        val message = LuaPandaMessage(LuaPandaCommands.CONTINUE, null, "0")
        println("[LuaPanda] 发送继续运行消息: ${Gson().toJson(message)}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        transporter?.sendMessage(message)
    }

    override fun stop() {
        println("[LuaPanda] 停止调试会话", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        val message = LuaPandaMessage(LuaPandaCommands.STOP_RUN, null, "0")
        println("[LuaPanda] 发送停止运行消息: ${Gson().toJson(message)}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        transporter?.sendMessage(message)
        transporter?.stop()
        transporter = null
        println("[LuaPanda] 传输器已停止", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    override fun startStepOver(context: XSuspendContext?) {
        println("[LuaPanda] 执行单步跳过命令", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        val message = LuaPandaMessage(LuaPandaCommands.STEP_OVER, null, "0")
        println("[LuaPanda] 发送单步跳过消息: ${Gson().toJson(message)}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        transporter?.sendMessage(message)
    }

    override fun startStepInto(context: XSuspendContext?) {
        println("[LuaPanda] 执行单步进入命令", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        val message = LuaPandaMessage(LuaPandaCommands.STEP_IN, null, "0")
        println("[LuaPanda] 发送单步进入消息: ${Gson().toJson(message)}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        transporter?.sendMessage(message)
    }

    override fun startStepOut(context: XSuspendContext?) {
        println("[LuaPanda] 执行单步跳出命令", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        val message = LuaPandaMessage(LuaPandaCommands.STEP_OUT, null, "0")
        println("[LuaPanda] 发送单步跳出消息: ${Gson().toJson(message)}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        transporter?.sendMessage(message)
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
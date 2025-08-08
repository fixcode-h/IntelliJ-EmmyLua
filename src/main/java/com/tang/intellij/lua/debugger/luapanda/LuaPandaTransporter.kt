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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.ui.ConsoleViewContentType
import com.tang.intellij.lua.debugger.DebugLogger
import com.tang.intellij.lua.debugger.LogConsoleType
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// ========== 枚举和接口定义 ==========

/**
 * 日志级别枚举
 */
enum class LogLevel(val value: Int, val description: String) {
    DEBUG(0, "调试日志"),
    CONNECTION(1, "连接状态日志"),
    ERROR(2, "错误日志")
}

/**
 * 传输器事件处理接口
 */
interface ILuaPandaTransportHandler {
    fun onReceiveMessage(message: LuaPandaMessage)
    fun onDisconnect()
    fun onConnect(success: Boolean)
}

// ========== 抽象传输器基类 ==========

/**
 * LuaPanda传输器抽象基类
 * 提供消息发送、回调管理、日志记录等基础功能
 */
abstract class LuaPandaTransporter(private val logger: DebugLogger? = null) {
    
    // ========== 属性定义 ==========
    
    private var messageHandler: ((LuaPandaMessage) -> Unit)? = null
    private var connectionHandler: ((Boolean) -> Unit)? = null
    protected var callbackCounter = 0
    protected val callbacks = ConcurrentHashMap<String, (LuaPandaMessage) -> Unit>()
    private var logLevel: Int = LogLevel.CONNECTION.value
    
    // ========== 抽象方法 ==========
    
    abstract fun start()
    abstract fun stop()
    abstract fun sendMessage(message: LuaPandaMessage)
    
    // ========== 配置方法 ==========
    
    fun setLogLevel(level: Int) {
        this.logLevel = level
    }
    
    fun setMessageHandler(handler: (LuaPandaMessage) -> Unit) {
        this.messageHandler = handler
    }
    
    fun setConnectionHandler(handler: (Boolean) -> Unit) {
        this.connectionHandler = handler
    }
    
    // ========== 消息发送方法 ==========
    
    /**
     * 发送带回调的消息
     */
    fun sendMessage(message: LuaPandaMessage, callback: (LuaPandaMessage?) -> Unit) {
        val callbackId = generateCallbackId()
        val messageWithCallback = LuaPandaMessage(message.cmd, message.info, callbackId, message.stack)
        registerCallback(callbackId) { response -> callback(response) }
        sendMessage(messageWithCallback)
    }
    
    /**
     * 向调试器发送命令 - 仿照VSCode插件的实现
     * @param cmd 发给Debugger的命令
     * @param sendObject 消息参数，会被放置在协议的info中
     * @param callbackFunc 回调函数
     * @param timeOutSec 超时时间（秒）
     */
    fun commandToDebugger(
        cmd: String, 
        sendObject: Any? = null, 
        callbackFunc: ((LuaPandaMessage) -> Unit)? = null, 
        timeOutSec: Int = 0
    ) {
        val callbackId = if (callbackFunc != null) {
            val id = generateUniqueCallbackId()
            registerCallback(id, callbackFunc)
            
            // TODO: 实现超时机制
            if (timeOutSec > 0) {
                // 超时处理逻辑
            }
            
            id
        } else {
            "0" // 没有回调时使用默认值
        }
        
        val message = LuaPandaMessage(
            cmd = cmd,
            info = if (sendObject != null) Gson().toJsonTree(sendObject) else JsonObject(),
            callbackId = callbackId,
            stack = null
        )
        
        sendMessage(message)
    }
    
    // ========== 回调管理 ==========
    
    fun generateCallbackId(): String {
        return (++callbackCounter).toString()
    }
    
    /**
     * 生成唯一的回调ID - 仿照VSCode插件的实现
     */
    private fun generateUniqueCallbackId(): String {
        val max = 999999999
        val min = 10  // 10以内是保留位
        var ranNum: Int
        
        do {
            ranNum = (Math.random() * (max - min + 1) + min).toInt()
        } while (callbacks.containsKey(ranNum.toString()))
        
        return ranNum.toString()
    }
    
    fun registerCallback(callbackId: String, callback: (LuaPandaMessage) -> Unit) {
        callbacks[callbackId] = callback
    }
    
    // ========== 消息处理 ==========
    
    protected fun handleReceivedMessage(message: LuaPandaMessage) {
        // 检查是否是回调消息
        val callback = callbacks.remove(message.callbackId)
        if (callback != null) {
            callback(message)
        } else {
            messageHandler?.invoke(message)
        }
    }
    
    // ========== 连接状态通知 ==========
    
    protected fun notifyConnect(success: Boolean) {
        connectionHandler?.invoke(success)
    }
    
    protected fun notifyDisconnect() {
        connectionHandler?.invoke(false)
    }
    
    // ========== 日志工具方法 ==========
    
    /**
     * 根据日志级别打印信息日志
     */
    protected fun logInfo(message: String, level: LogLevel = LogLevel.CONNECTION) {
        if (level.value >= logLevel) {
            logger?.println(message, LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT) ?: println(message)
        }
    }
    
    /**
     * 打印错误日志
     */
    protected fun logError(message: String, level: LogLevel = LogLevel.ERROR) {
        if (level.value >= logLevel) {
            logger?.println(message, LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT) ?: println(message)
        }
    }
    
    /**
     * 格式化JSON用于日志显示
     */
    protected fun formatJsonForLog(json: String): String {
        return json.replace("\n", "\\n").replace("\r", "\\r")
    }
}

// ========== TCP客户端传输器 ==========

/**
 * TCP客户端传输器实现
 * 支持自动重连和连接超时控制
 */
class LuaPandaTcpClientTransporter(
    private val host: String, 
    private val port: Int, 
    private val autoReconnect: Boolean = true, 
    logger: DebugLogger? = null
) : LuaPandaTransporter(logger) {
    
    // ========== 属性定义 ==========
    
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false
    private var connectThread: Thread? = null
    private var isConnected = false
    private var connectionFlag = false
    
    companion object {
        private const val CONNECTION_TIMEOUT = 800 // 连接超时时间（毫秒）
        private const val MAX_RETRY_COUNT = 30     // 最大重试次数
        private const val RETRY_DELAY = 1000       // 重试延迟（毫秒）
        private const val PROTOCOL_SEPARATOR = "|*|"
    }
    
    // ========== 生命周期管理 ==========
    
    override fun start() {
        logInfo("TCP客户端开始连接 $host:$port (自动重连: $autoReconnect)", LogLevel.CONNECTION)
        isRunning = true
        connectionFlag = false
        
        connectThread = Thread { runConnectionLoop() }
        connectThread?.start()
    }
    
    override fun stop() {
        logInfo("TCP客户端开始停止流程", LogLevel.CONNECTION)
        
        isRunning = false
        connectionFlag = false
        
        // 给正在进行的操作一点时间完成
        Thread.sleep(100)
        
        cleanupConnection()
        
        try {
            connectThread?.interrupt()
        } catch (e: Exception) {
            logError("中断连接线程异常: ${e.message}", LogLevel.CONNECTION)
        }
        
        logInfo("TCP客户端停止流程完成", LogLevel.CONNECTION)
    }
    
    // ========== 连接管理 ==========
    
    private fun runConnectionLoop() {
        var retryCount = 0
        
        while (isRunning && !isConnected) {
            if (!autoReconnect && retryCount >= MAX_RETRY_COUNT) {
                logError("连接失败，已达到最大重试次数", LogLevel.CONNECTION)
                notifyConnect(false)
                break
            }
            
            try {
                attemptConnection(retryCount)
                if (isConnected) {
                    handleConnection()
                    
                    // 连接断开后的处理
                    if (isRunning && connectionFlag) {
                        logInfo("连接意外断开，通知调试进程", LogLevel.CONNECTION)
                        connectionFlag = false
                        isConnected = false
                        notifyDisconnect()
                    }
                    
                    if (!autoReconnect) break
                    cleanupConnection()
                }
            } catch (e: Exception) {
                retryCount++
                handleConnectionError(e, retryCount)
                
                if (!autoReconnect && retryCount >= MAX_RETRY_COUNT) {
                    notifyConnect(false)
                    break
                }
                
                waitForRetry(retryCount)
            }
        }
    }
    
    private fun attemptConnection(retryCount: Int) {
        if (retryCount > 0) {
            logInfo("尝试连接 $host:$port (第${retryCount + 1}次)", LogLevel.CONNECTION)
        } else {
            logInfo("尝试连接 $host:$port", LogLevel.CONNECTION)
        }
        
        socket = Socket()
        socket!!.connect(java.net.InetSocketAddress(host, port), CONNECTION_TIMEOUT)
        socket!!.soTimeout = 0 // 无限等待，避免读取超时
        
        writer = PrintWriter(socket!!.getOutputStream(), true)
        reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
        
        logInfo("TCP客户端连接成功", LogLevel.CONNECTION)
        
        isConnected = true
        connectionFlag = true
        
        Thread.sleep(100) // 确保连接稳定
        notifyConnect(true)
    }
    
    private fun handleConnection() {
        while (isRunning && !socket!!.isClosed && isConnected && connectionFlag) {
            try {
                val line = reader?.readLine()
                if (line != null) {
                    processReceivedMessage(line)
                } else {
                    logInfo("检测到连接断开（readLine返回null）", LogLevel.CONNECTION)
                    break
                }
            } catch (e: java.net.SocketTimeoutException) {
                continue // 读取超时，继续循环
            } catch (e: Exception) {
                logInfo("检测到连接断开（读取异常）: ${e.message}", LogLevel.CONNECTION)
                break
            }
        }
    }
    
    private fun handleConnectionError(e: Exception, retryCount: Int) {
        cleanupConnection()
        
        val retryInfo = if (autoReconnect) "1秒后重试" else "1秒后重试 ($retryCount/$MAX_RETRY_COUNT)"
        
        when (e) {
            is java.net.ConnectException -> {
                logInfo("连接被拒绝，$retryInfo", LogLevel.CONNECTION)
            }
            is java.net.SocketTimeoutException -> {
                logInfo("连接超时，$retryInfo", LogLevel.CONNECTION)
            }
            else -> {
                logError("TCP客户端连接异常: ${e.message}", LogLevel.CONNECTION)
                logInfo(retryInfo, LogLevel.CONNECTION)
            }
        }
    }
    
    private fun waitForRetry(retryCount: Int) {
        try {
            Thread.sleep(RETRY_DELAY.toLong())
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
    
    private fun cleanupConnection() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        socket = null
        writer = null
        reader = null
        isConnected = false
        connectionFlag = false
    }
    
    // ========== 消息处理 ==========
    
    private fun processReceivedMessage(line: String) {
        try {
            val jsonString = line.removeSuffix(PROTOCOL_SEPARATOR)
            val displayJson = formatJsonForLog(jsonString)
            logInfo("接收协议: $displayJson", LogLevel.DEBUG)
            
            val message = Gson().fromJson(jsonString, LuaPandaMessage::class.java)
            handleReceivedMessage(message)
        } catch (e: Exception) {
            logError("消息解析失败: ${e.message}", LogLevel.ERROR)
        }
    }
    
    override fun sendMessage(message: LuaPandaMessage) {
        try {
            val json = Gson().toJson(message)
            val finalMessage = "$json$PROTOCOL_SEPARATOR"
            val displayJson = formatJsonForLog(json)
            
            logInfo("发送协议: $displayJson", LogLevel.DEBUG)
            writer?.println(finalMessage)
        } catch (e: Exception) {
            logError("消息发送失败: ${e.message}", LogLevel.ERROR)
        }
    }
}

// ========== TCP服务器传输器 ==========

/**
 * TCP服务器传输器实现
 * 支持客户端重连和连接管理
 */
class LuaPandaTcpServerTransporter(
    private val port: Int, 
    logger: DebugLogger? = null
) : LuaPandaTransporter(logger) {
    
    // ========== 属性定义 ==========
    
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false
    
    companion object {
        private const val PROTOCOL_SEPARATOR = "|*|"
        private const val RECONNECT_DELAY = 1000L
    }
    
    // ========== 生命周期管理 ==========
    
    override fun start() {
        logInfo("TCP服务器监听端口 $port", LogLevel.CONNECTION)
        
        Thread { runServerLoop() }.start()
    }
    
    override fun stop() {
        logInfo("TCP服务器开始停止流程", LogLevel.DEBUG)
        
        isRunning = false
        
        // 给正在进行的操作一点时间完成
        Thread.sleep(100)
        
        cleanupAllConnections()
        
        logInfo("TCP服务器停止流程完成", LogLevel.CONNECTION)
    }
    
    // ========== 服务器循环 ==========
    
    private fun runServerLoop() {
        try {
            serverSocket = ServerSocket(port)
            isRunning = true
            
            while (isRunning) {
                try {
                    waitForClientConnection()
                    if (clientSocket != null) {
                        handleClientConnection()
                        cleanupClientConnection()
                        
                        if (isRunning) {
                            logInfo("客户端连接断开，等待重新连接...", LogLevel.CONNECTION)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        logError("接受客户端连接失败: ${e.message}", LogLevel.ERROR)
                        Thread.sleep(RECONNECT_DELAY)
                    }
                }
            }
        } catch (e: Exception) {
            logError("TCP服务器启动失败: ${e.message}", LogLevel.ERROR)
            notifyConnect(false)
        }
    }
    
    private fun waitForClientConnection() {
        logInfo("等待客户端连接...", LogLevel.DEBUG)
        clientSocket = serverSocket!!.accept()
        logInfo("客户端已连接: ${clientSocket!!.remoteSocketAddress}", LogLevel.CONNECTION)
        
        writer = PrintWriter(clientSocket!!.getOutputStream(), true)
        reader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
        
        notifyConnect(true)
    }
    
    private fun handleClientConnection() {
        while (isRunning && !clientSocket!!.isClosed) {
            try {
                val line = reader?.readLine()
                if (line != null) {
                    processReceivedMessage(line)
                } else {
                    logInfo("检测到客户端断开连接（readLine返回null），准备重新连接", LogLevel.DEBUG)
                    break
                }
            } catch (e: Exception) {
                logInfo("检测到客户端断开连接（读取异常）: ${e.message}，准备重新连接", LogLevel.ERROR)
                break
            }
        }
    }
    
    // ========== 连接清理 ==========
    
    private fun cleanupClientConnection() {
        listOf(writer, reader, clientSocket).forEach { resource ->
            try {
                when (resource) {
                    is PrintWriter -> resource.close()
                    is BufferedReader -> resource.close()
                    is Socket -> resource.close()
                }
            } catch (e: Exception) {
                // 忽略关闭异常
            }
        }
        
        writer = null
        reader = null
        clientSocket = null
    }
    
    private fun cleanupAllConnections() {
        cleanupClientConnection()
        
        try {
            serverSocket?.close()
            logInfo("服务器Socket已关闭", LogLevel.DEBUG)
        } catch (e: Exception) {
            logInfo("关闭服务器Socket异常: ${e.message}", LogLevel.CONNECTION)
        }
        
        serverSocket = null
    }
    
    // ========== 消息处理 ==========
    
    private fun processReceivedMessage(line: String) {
        try {
            val jsonString = line.removeSuffix(PROTOCOL_SEPARATOR)
            val displayJson = formatJsonForLog(jsonString)
            logInfo("接收协议: $displayJson", LogLevel.DEBUG)
            
            val message = Gson().fromJson(jsonString, LuaPandaMessage::class.java)
            handleReceivedMessage(message)
        } catch (e: Exception) {
            logError("消息解析失败: ${e.message}", LogLevel.ERROR)
        }
    }
    
    override fun sendMessage(message: LuaPandaMessage) {
        try {
            val json = Gson().toJson(message)
            val finalMessage = "$json$PROTOCOL_SEPARATOR"
            val displayJson = formatJsonForLog(json)
            
            logInfo("发送协议: $displayJson", LogLevel.DEBUG)
            writer?.println(finalMessage)
        } catch (e: Exception) {
            logError("消息发送失败: ${e.message}", LogLevel.ERROR)
        }
    }
}
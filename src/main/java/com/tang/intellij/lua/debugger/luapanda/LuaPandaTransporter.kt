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
    private var b64EncodeEnabled: Boolean = true // 默认启用Base64编码
    protected abstract val writer: PrintWriter? // 添加抽象的writer属性
    
    companion object {
        protected const val PROTOCOL_SEPARATOR = "|*|"
    }
    
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
    
    /**
     * 设置Base64编码状态
     * 用于处理字符串中的特殊字符
     */
    fun enableB64Encoding(enabled: Boolean) {
        this.b64EncodeEnabled = enabled
        logInfo("Base64编码设置为: ${if (enabled) "启用" else "禁用"}", LogLevel.DEBUG)
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
        
        // 参照VSCode插件的实现：sendObj["cmd"] = cmd; sendObj["info"] = sendObject;
        val sendObj = mutableMapOf<String, Any?>()
        sendObj["cmd"] = cmd
        sendObj["callbackId"] = callbackId
        
        // 将sendObject的内容直接作为info字段，而不是嵌套对象
        if (sendObject != null) {
            sendObj["info"] = sendObject
        }
        
        // 直接构造JSON字符串并发送，而不是通过LuaPandaMessage对象
        try {
            val json = Gson().toJson(sendObj)
            val finalMessage = "$json $PROTOCOL_SEPARATOR\n"
            val displayJson = formatJsonForLog(json)
            
            logInfo("发送协议: $displayJson", LogLevel.DEBUG)
            
            // 直接使用writer属性发送消息
            writer?.print(finalMessage)
            writer?.flush()
            
        } catch (e: Exception) {
            logError("发送命令失败: ${e.message}", LogLevel.ERROR)
        }
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
    
    // ========== 消息处理方法 ==========
    
    protected fun notifyConnect(connected: Boolean) {
        connectionHandler?.invoke(connected)
    }
    
    protected fun notifyDisconnect() {
        connectionHandler?.invoke(false)
    }
    
    /**
     * 处理接收到的消息
     * 参照VSCode插件的dataProcessor.processMsg实现
     */
    protected fun handleReceivedMessage(message: LuaPandaMessage) {
        try {
            // 如果启用了Base64编码，解码字符串类型的info
            if (b64EncodeEnabled && message.info != null && message.info is com.google.gson.JsonObject) {
                decodeBase64StringsInInfo(message.info as com.google.gson.JsonObject)
            }
            
            // 检查是否有回调ID（对应VSCode插件中的callbackId处理）
            val callbackId = message.callbackId
            if (callbackId != null && callbackId != "0") {
                // 处理回调响应
                handleCallbackResponse(callbackId, message)
            } else {
                // 处理普通消息
                handleNormalMessage(message)
            }
            
            // 清理超时的回调（参照VSCode插件的超时处理）
            cleanupTimeoutCallbacks()
            
        } catch (e: Exception) {
            logError("处理消息时出错: ${e.message}", LogLevel.ERROR)
        }
    }
    
    /**
     * 解码info中的Base64字符串
     * 参照VSCode插件的Base64解码逻辑
     */
    private fun decodeBase64StringsInInfo(info: com.google.gson.JsonObject) {
        try {
            // 如果info是JsonArray形式
            val infoArray = info.getAsJsonArray("info")
            infoArray?.forEach { element ->
                if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    if (obj.has("type") && obj.get("type").asString == "string" && obj.has("value")) {
                        try {
                            val encodedValue = obj.get("value").asString
                            val decodedValue = String(java.util.Base64.getDecoder().decode(encodedValue))
                            obj.addProperty("value", decodedValue)
                        } catch (e: Exception) {
                            logInfo("Base64解码失败，保持原值: ${e.message}", LogLevel.DEBUG)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 如果不是期望的格式，忽略解码
            logInfo("跳过Base64解码: ${e.message}", LogLevel.DEBUG)
        }
    }
    
    /**
     * 处理回调响应
     */
    private fun handleCallbackResponse(callbackId: String, message: LuaPandaMessage) {
        val callback = callbacks.remove(callbackId)
        if (callback != null) {
            logInfo("执行回调 ID: $callbackId", LogLevel.DEBUG)
            callback(message)
        } else {
            logError("未找到回调 ID: $callbackId", LogLevel.DEBUG)
        }
    }
    
    /**
     * 处理普通消息
     */
    private fun handleNormalMessage(message: LuaPandaMessage) {
        messageHandler?.invoke(message)
    }
    
    /**
     * 清理超时的回调（简化版，实际项目中可以实现更复杂的超时机制）
     */
    private fun cleanupTimeoutCallbacks() {
        // 这里可以实现超时清理逻辑
        // 参照VSCode插件中的timeOut处理
    }
    
    /**
     * 清理所有回调
     * 用于停止调试时清理资源
     */
    fun clearCallbacks() {
        callbacks.clear()
        logInfo("已清理所有待处理的回调", LogLevel.DEBUG)
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
    private var _writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false
    private var connectThread: Thread? = null
    private var isConnected = false
    private var connectionFlag = false
    
    // 实现抽象的writer属性
    override val writer: PrintWriter? get() = _writer
    
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
        
        cleanupConnection()
        notifyDisconnect()
        
        connectThread?.interrupt()
        connectThread = null
        
        logInfo("TCP客户端已停止", LogLevel.CONNECTION)
    }
    
    /**
     * 停止重连尝试
     * 用于调试停止时立即停止重连机制
     */
    fun stopReconnectAttempts() {
        logInfo("停止重连尝试", LogLevel.DEBUG)
        isRunning = false
        connectionFlag = false
        connectThread?.interrupt()
    }
    
    // ========== 连接管理 ==========
    
    private fun runConnectionLoop() {
        var retryCount = 0
        
        while (isRunning && (!connectionFlag || autoReconnect)) {
            try {
                attemptConnection(retryCount)
                handleConnection()
                
                // 连接断开后的处理
                if (isRunning && autoReconnect) {
                    // 只有在启用自动重连时才重试
                    logInfo("连接断开，1秒后尝试重新连接...", LogLevel.CONNECTION)
                    waitForRetry(retryCount)
                    retryCount++
                    
                    if (retryCount >= MAX_RETRY_COUNT) {
                        logInfo("已达到最大重试次数 ($MAX_RETRY_COUNT)，停止重连尝试", LogLevel.CONNECTION)
                        break
                    }
                } else if (!autoReconnect) {
                    logInfo("自动重连已禁用，停止连接尝试", LogLevel.CONNECTION)
                    break
                }
                
            } catch (e: Exception) {
                handleConnectionError(e, retryCount)
                
                if (!autoReconnect) {
                    logInfo("自动重连已禁用，连接失败后停止尝试", LogLevel.CONNECTION)
                    break
                }
                
                retryCount++
                if (retryCount >= MAX_RETRY_COUNT) {
                    logInfo("已达到最大重试次数 ($MAX_RETRY_COUNT)，停止连接尝试", LogLevel.CONNECTION)
                    break
                }
                
                waitForRetry(retryCount)
            }
        }
        
        // 连接循环结束，通知断开
        if (connectionFlag) {
            connectionFlag = false
            logInfo("连接循环结束", LogLevel.CONNECTION)
            notifyConnect(false)
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
        
        _writer = PrintWriter(socket!!.getOutputStream(), true)
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
        _writer = null
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
            // 参照VSCode插件格式：JSON + " " + 分隔符 + "\n"
            val finalMessage = "$json $PROTOCOL_SEPARATOR\n"
            val displayJson = formatJsonForLog(json)
            
            logInfo("发送协议: $displayJson", LogLevel.DEBUG)
            writer?.print(finalMessage) // 使用print而不是println，因为已经包含换行符
            writer?.flush() // 确保立即发送
        } catch (e: Exception) {
            logError("消息发送失败: ${e.message}", LogLevel.ERROR)
        }
    }
}

/**
 * TCP服务器传输器实现
 * 监听指定端口，等待客户端连接
 */
class LuaPandaTcpServerTransporter(
    private val port: Int, 
    private val autoReconnect: Boolean = true,
    logger: DebugLogger? = null
) : LuaPandaTransporter(logger) {
    
    // ========== 属性定义 ==========
    
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var _writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false
    private var serverThread: Thread? = null
    
    // 实现抽象的writer属性
    override val writer: PrintWriter? get() = _writer
    
    companion object {
        private const val RECONNECT_DELAY = 1000L // 重连延迟（毫秒）
        private const val PROTOCOL_SEPARATOR = "|*|"
    }
    
    // ========== 生命周期管理 ==========
    
    override fun start() {
        logInfo("TCP服务器监听端口 $port", LogLevel.CONNECTION)
        
        // 确保之前的线程已经停止
        if (serverThread?.isAlive == true) {
            logInfo("停止之前的服务器线程", LogLevel.DEBUG)
            stop()
        }
        
        serverThread = Thread { runServerLoop() }
        serverThread?.name = "LuaPanda-TCP-Server-$port"
        serverThread?.start()
    }
    
    override fun stop() {
        logInfo("TCP服务器开始停止流程", LogLevel.DEBUG)
        
        isRunning = false
        
        // 首先关闭服务器Socket，停止接受新连接
        try {
            serverSocket?.close()
            logInfo("服务器Socket已关闭", LogLevel.DEBUG)
        } catch (e: Exception) {
            logError("关闭服务器Socket时出错: ${e.message}", LogLevel.DEBUG)
        }
        
        // 清理所有连接
        cleanupAllConnections()
        
        // 等待服务器线程结束
        try {
            serverThread?.join(2000) // 等待最多2秒
            if (serverThread?.isAlive == true) {
                logInfo("服务器线程未在2秒内结束，强制中断", LogLevel.DEBUG)
                serverThread?.interrupt()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        serverThread = null
        logInfo("TCP服务器停止流程完成", LogLevel.CONNECTION)
    }
    
    // ========== 服务器循环 ==========
    
    private fun runServerLoop() {
        try {
            serverSocket = ServerSocket()
            // 设置端口重用，避免"Address already in use"错误
            serverSocket!!.reuseAddress = true
            serverSocket!!.bind(java.net.InetSocketAddress(port))
            
            isRunning = true
            logInfo("TCP服务器成功绑定端口 $port", LogLevel.DEBUG)
            
            while (isRunning) {
                try {
                    waitForClientConnection()
                    if (clientSocket != null) {
                        handleClientConnection()
                        cleanupClientConnection()
                        
                        // 通知连接断开
                        notifyConnect(false)
                        
                        if (isRunning) {
                            if (autoReconnect) {
                                logInfo("客户端连接断开，等待重新连接...", LogLevel.CONNECTION)
                            } else {
                                logInfo("客户端连接断开，自动重连已禁用，停止服务器", LogLevel.CONNECTION)
                                break
                            }
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
        } finally {
            // 确保服务器Socket正确关闭
            try {
                serverSocket?.close()
                logInfo("服务器Socket已关闭", LogLevel.DEBUG)
            } catch (e: Exception) {
                logError("关闭服务器Socket时出错: ${e.message}", LogLevel.DEBUG)
            }
        }
    }
    
    private fun waitForClientConnection() {
        logInfo("等待客户端连接...", LogLevel.DEBUG)
        clientSocket = serverSocket!!.accept()
        logInfo("客户端已连接: ${clientSocket!!.remoteSocketAddress}", LogLevel.CONNECTION)
        
        _writer = PrintWriter(clientSocket!!.getOutputStream(), true)
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
        
        _writer = null
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
            // 参照VSCode插件格式：JSON + " " + 分隔符 + "\n"
            val finalMessage = "$json $PROTOCOL_SEPARATOR\n"
            val displayJson = formatJsonForLog(json)
            
            logInfo("发送协议: $displayJson", LogLevel.DEBUG)
            writer?.print(finalMessage) // 使用print而不是println，因为已经包含换行符
            writer?.flush() // 确保立即发送
        } catch (e: Exception) {
            logError("消息发送失败: ${e.message}", LogLevel.ERROR)
        }
    }
}
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

import com.google.gson.JsonObject
import com.tang.intellij.lua.debugger.DebugLogLevel
import com.tang.intellij.lua.debugger.DebugLogger
import com.tang.intellij.lua.debugger.core.RequestBroker
import com.tang.intellij.lua.debugger.core.RequestRegistry
import com.tang.intellij.lua.debugger.transport.ConnectionEpochTracker
import com.tang.intellij.lua.debugger.transport.TransportChannel
import com.tang.intellij.lua.debugger.transport.TransportConnectionEvent
import com.tang.intellij.lua.debugger.transport.TransportConnectionState
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// ========== 枚举和接口定义 ==========

private typealias LogLevel = DebugLogLevel
typealias LuaPandaConnectionState = TransportConnectionState
typealias LuaPandaConnectionEvent = TransportConnectionEvent

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
abstract class LuaPandaTransporter(private val logger: DebugLogger? = null) : TransportChannel<LuaPandaMessage> {
    
    // ========== 属性定义 ==========
    
    private var messageHandler: ((LuaPandaMessage) -> Unit)? = null
    private var connectionHandler: ((LuaPandaConnectionEvent) -> Unit)? = null
    private var eventDispatcher: ((() -> Unit) -> Unit) = { action -> action() }
    private val callbackCounter = AtomicLong()
    private val connectionEpochs = ConnectionEpochTracker()
    private val terminalDisconnectNotified = AtomicBoolean()
    private val requestBroker = RequestBroker(
        RequestRegistry<LuaPandaMessage>(
            defaultTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS,
            scheduler = REQUEST_TIMEOUT_SCHEDULER,
            closeScheduler = false,
            callbackErrorHandler = { error -> logError("请求回调执行失败: ${error.message}", LogLevel.ERROR) }
        )
    )
    private var b64EncodeEnabled: Boolean = true // 默认启用Base64编码
    protected val codec = LuaPandaWireCodec()
    protected abstract val writer: PrintWriter? // 添加抽象的writer属性
    
    companion object {
        private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L
        private val REQUEST_TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { task ->
                Thread(task, "LuaPanda-RequestTimeout").apply { isDaemon = true }
            }
        )
    }
    
    // ========== 抽象方法 ==========
    
    abstract override fun start()
    abstract fun stop()
    abstract override fun send(message: LuaPandaMessage)

    final override fun close() = stop()
    
    // ========== 配置方法 ==========
    
    fun setMessageHandler(handler: (LuaPandaMessage) -> Unit) {
        this.messageHandler = handler
    }
    
    fun setConnectionHandler(handler: (LuaPandaConnectionEvent) -> Unit) {
        this.connectionHandler = handler
    }

    fun setEventDispatcher(dispatcher: (() -> Unit) -> Unit) {
        eventDispatcher = dispatcher
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
        requestBroker.request(
            requestId = callbackId,
            send = { send(messageWithCallback) }
        ) { result ->
            if (result.isSuccess) eventDispatcher { callback(result.getOrNull()) }
            else eventDispatcher { callback(null) }
        }
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
        timeOutSec: Int = 0,
        failureFunc: ((Throwable) -> Unit)? = null
    ) {
        val callbackId = if (callbackFunc != null) {
            generateUniqueCallbackId()
        } else {
            "0" // 没有回调时使用默认值
        }
        
        // 参照VSCode插件的实现：sendObj["cmd"] = cmd; sendObj["info"] = sendObject;
        val sendAction = {
            val encoded = codec.encodeCommand(cmd, callbackId, sendObject)
            logInfo("发送协议: ${formatJsonForLog(encoded.json)}", LogLevel.DEBUG)
            writePayload(encoded.payload)
        }
        try {
            if (callbackFunc == null) {
                sendAction()
            } else {
                val timeoutMillis = if (timeOutSec > 0) timeOutSec * 1_000L else DEFAULT_REQUEST_TIMEOUT_MILLIS
                requestBroker.request(callbackId, timeoutMillis, sendAction) { result ->
                    result.onSuccess { response ->
                        eventDispatcher { callbackFunc(response) }
                    }.onFailure { error ->
                        logError("请求 $cmd 失败: ${error.message}", LogLevel.DEBUG)
                        eventDispatcher { failureFunc?.invoke(error) }
                    }
                }
            }
        } catch (e: Exception) {
            logError("发送命令失败: ${e.message}", LogLevel.ERROR)
            throw e
        }
    }
    
    // ========== 回调管理 ==========
    
    fun generateCallbackId(): String {
        return callbackCounter.incrementAndGet().toString()
    }
    
    /**
     * 生成唯一的回调ID - 仿照VSCode插件的实现
     */
    private fun generateUniqueCallbackId(): String {
        var id: String
        do {
            id = generateCallbackId()
        } while (requestBroker.contains(id))
        return id
    }
    
    // ========== 消息处理方法 ==========
    
    protected fun notifyConnected(): Long {
        val epoch = connectionEpochs.next()
        terminalDisconnectNotified.set(false)
        eventDispatcher {
            connectionHandler?.invoke(LuaPandaConnectionEvent(LuaPandaConnectionState.CONNECTED, epoch))
        }
        return epoch
    }

    protected fun notifyReconnecting(retryAttempt: Int, cause: Throwable? = null) {
        val failure = cause ?: IOException("LuaPanda transport reconnecting")
        requestBroker.cancelAll(failure)
        eventDispatcher {
            connectionHandler?.invoke(
                LuaPandaConnectionEvent(
                    LuaPandaConnectionState.RECONNECTING,
                    connectionEpochs.current,
                    retryAttempt,
                    cause
                )
            )
        }
    }

    protected fun notifyDisconnected(cause: Throwable? = null) {
        if (!terminalDisconnectNotified.compareAndSet(false, true)) return
        val failure = cause ?: IOException("LuaPanda transport disconnected")
        requestBroker.cancelAll(failure)
        eventDispatcher {
            connectionHandler?.invoke(
                LuaPandaConnectionEvent(
                    LuaPandaConnectionState.DISCONNECTED,
                    connectionEpochs.current,
                    cause = cause
                )
            )
        }
    }

    protected fun resetConnectionEvents() {
        terminalDisconnectNotified.set(false)
    }

    @Throws(IOException::class)
    protected fun writePayload(payload: String) {
        val activeWriter = writer ?: throw IOException("Transport is not connected")
        synchronized(activeWriter) {
            activeWriter.print(payload)
            activeWriter.flush()
            if (activeWriter.checkError()) throw IOException("LuaPanda transport write failed")
        }
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
            if (callbackId.isNotEmpty() && callbackId != "0") {
                // 处理回调响应
                handleCallbackResponse(callbackId, message)
            } else {
                // 处理普通消息
                handleNormalMessage(message)
            }
            
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
        if (requestBroker.complete(callbackId, message)) {
            logInfo("执行回调 ID: $callbackId", LogLevel.DEBUG)
        } else {
            logError("未找到回调 ID: $callbackId", LogLevel.DEBUG)
        }
    }
    
    /**
     * 处理普通消息
     */
    private fun handleNormalMessage(message: LuaPandaMessage) {
        eventDispatcher { messageHandler?.invoke(message) }
    }
    
    /**
     * 清理所有回调
     * 用于停止调试时清理资源
     */
    fun clearCallbacks() {
        requestBroker.cancelAll(CancellationException("LuaPanda transport stopped"))
        logInfo("已清理所有待处理的回调", LogLevel.DEBUG)
    }

    internal val pendingRequestCount: Int
        get() = requestBroker.pendingCount
    
    // ========== 日志工具方法 ==========
    
    /**
     * 根据日志级别打印信息日志
     */
    protected fun logInfo(message: String, level: LogLevel = LogLevel.RUNTIME) {
        logger?.log(message, level)
    }
    
    /**
     * 打印错误日志
     */
    protected fun logError(message: String, level: LogLevel = LogLevel.ERROR) {
        logger?.log(message, level)
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
    @Volatile private var isRunning = false
    private var connectThread: Thread? = null
    @Volatile private var isConnected = false
    @Volatile private var connectionFlag = false
    @Volatile internal var connectionEpoch: Long = 0
        private set
    
    // 实现抽象的writer属性
    override val writer: PrintWriter? get() = _writer
    
    companion object {
        private const val CONNECTION_TIMEOUT = 800 // 连接超时时间（毫秒）
        private const val MAX_RETRY_COUNT = 30     // 最大重试次数
        private const val RETRY_DELAY = 1000       // 重试延迟（毫秒）
    }
    
    // ========== 生命周期管理 ==========
    
    override fun start() {
        if (isRunning) return
        logInfo("TCP客户端开始连接 $host:$port (自动重连: $autoReconnect)", LogLevel.RUNTIME)
        isRunning = true
        connectionFlag = false
        resetConnectionEvents()

        connectThread = Thread({ runConnectionLoop() }, "LuaPanda-TCP-Client-$host-$port")
        connectThread?.start()
    }
    
    override fun stop() {
        logInfo("TCP客户端开始停止流程", LogLevel.RUNTIME)
        
        isRunning = false
        connectionFlag = false

        cleanupConnection()
        connectThread?.interrupt()
        connectThread = null
        notifyDisconnected(CancellationException("LuaPanda TCP client stopped"))

        logInfo("TCP客户端已停止", LogLevel.RUNTIME)
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

        while (isRunning) {
            try {
                attemptConnection(retryCount)
                retryCount = 0
                handleConnection()

                cleanupConnection()
                if (!isRunning) break
                if (!autoReconnect) {
                    logInfo("自动重连已禁用，停止连接尝试", LogLevel.RUNTIME)
                    notifyDisconnected(EOFException("LuaPanda TCP connection closed"))
                    break
                }

                retryCount = 1
                logInfo("连接断开，1秒后尝试重新连接...", LogLevel.RUNTIME)
                notifyReconnecting(retryCount, EOFException("LuaPanda TCP connection closed"))
                waitForRetry()
            } catch (e: Exception) {
                cleanupConnection()
                if (!isRunning) break

                retryCount++
                handleConnectionError(e, retryCount)

                if (!autoReconnect || retryCount >= MAX_RETRY_COUNT) {
                    if (retryCount >= MAX_RETRY_COUNT) {
                        logInfo("已达到最大重试次数 ($MAX_RETRY_COUNT)，停止连接尝试", LogLevel.RUNTIME)
                    } else {
                        logInfo("自动重连已禁用，连接失败后停止尝试", LogLevel.RUNTIME)
                    }
                    notifyDisconnected(e)
                    break
                } else {
                    notifyReconnecting(retryCount, e)
                    waitForRetry()
                }
            }
        }

        cleanupConnection()
    }

    private fun attemptConnection(retryCount: Int) {
        if (retryCount > 0) {
            logInfo("尝试连接 $host:$port (第${retryCount + 1}次)", LogLevel.RUNTIME)
        } else {
            logInfo("尝试连接 $host:$port", LogLevel.RUNTIME)
        }

        val newSocket = Socket()
        try {
            newSocket.connect(java.net.InetSocketAddress(host, port), CONNECTION_TIMEOUT)
            newSocket.soTimeout = 0
            val newWriter = PrintWriter(newSocket.getOutputStream(), true)
            val newReader = BufferedReader(InputStreamReader(newSocket.getInputStream()))

            socket = newSocket
            _writer = newWriter
            reader = newReader
            isConnected = true
            connectionFlag = true
            connectionEpoch = notifyConnected()
            logInfo("TCP客户端连接成功 (connectionEpoch=$connectionEpoch)", LogLevel.RUNTIME)
        } catch (error: Exception) {
            runCatching { newSocket.close() }
            throw error
        }
    }

    private fun handleConnection() {
        val activeSocket = socket ?: return
        val activeReader = reader ?: return
        while (isRunning && !activeSocket.isClosed && isConnected && connectionFlag) {
            val line = activeReader.readLine() ?: return
            processReceivedMessage(line)
        }
    }

    private fun handleConnectionError(e: Exception, retryCount: Int) {
        val retryInfo = if (autoReconnect && retryCount < MAX_RETRY_COUNT) {
            "1秒后重试 ($retryCount/$MAX_RETRY_COUNT)"
        } else {
            "停止连接"
        }

        when (e) {
            is java.net.ConnectException -> {
                logInfo("连接被拒绝，$retryInfo", LogLevel.RUNTIME)
            }
            is java.net.SocketTimeoutException -> {
                logInfo("连接超时，$retryInfo", LogLevel.RUNTIME)
            }
            else -> {
                logError("TCP客户端连接异常: ${e.message}", LogLevel.RUNTIME)
                logInfo(retryInfo, LogLevel.RUNTIME)
            }
        }
    }

    private fun waitForRetry() {
        try {
            Thread.sleep(RETRY_DELAY.toLong())
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun cleanupConnection() {
        runCatching { reader?.close() }
        runCatching { _writer?.close() }
        runCatching { socket?.close() }
        socket = null
        _writer = null
        reader = null
        isConnected = false
        connectionFlag = false
    }

    // ========== 消息处理 ==========

    private fun processReceivedMessage(line: String) {
        try {
            val jsonString = codec.extractJson(line)
            val displayJson = formatJsonForLog(jsonString)
            logInfo("接收协议: $displayJson", LogLevel.DEBUG)

            val message = codec.decode(line)
            handleReceivedMessage(message)
        } catch (e: Exception) {
            logError("消息解析失败: ${e.message}", LogLevel.ERROR)
        }
    }

    override fun send(message: LuaPandaMessage) {
        val encoded = codec.encode(message)

        logInfo("发送协议: ${formatJsonForLog(encoded.json)}", LogLevel.DEBUG)
        writePayload(encoded.payload)
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
    @Volatile private var isRunning = false
    private var serverThread: Thread? = null
    
    // 实现抽象的writer属性
    override val writer: PrintWriter? get() = _writer
    
    companion object {
        private const val RECONNECT_DELAY = 1000L // 重连延迟（毫秒）
    }
    
    // ========== 生命周期管理 ==========
    
    override fun start() {
        logInfo("TCP服务器监听端口 $port", LogLevel.RUNTIME)
        
        // 确保之前的线程已经停止
        if (serverThread?.isAlive == true) {
            logInfo("停止之前的服务器线程", LogLevel.DEBUG)
            stop()
        }

        isRunning = true
        resetConnectionEvents()
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
        notifyDisconnected(CancellationException("LuaPanda TCP server stopped"))
        logInfo("TCP服务器停止流程完成", LogLevel.RUNTIME)
    }
    
    // ========== 服务器循环 ==========
    
    private fun runServerLoop() {
        try {
            serverSocket = ServerSocket()
            // 设置端口重用，避免"Address already in use"错误
            serverSocket!!.reuseAddress = true
            serverSocket!!.bind(java.net.InetSocketAddress(port))
            
            logInfo("TCP服务器成功绑定端口 $port", LogLevel.DEBUG)
            
            while (isRunning) {
                try {
                    waitForClientConnection()
                    if (clientSocket != null) {
                        handleClientConnection()
                        cleanupClientConnection()
                        
                        if (isRunning) {
                            if (autoReconnect) {
                                logInfo("客户端连接断开，等待重新连接...", LogLevel.RUNTIME)
                                notifyReconnecting(1, EOFException("LuaPanda TCP client disconnected"))
                            } else {
                                logInfo("客户端连接断开，自动重连已禁用，停止服务器", LogLevel.RUNTIME)
                                notifyDisconnected(EOFException("LuaPanda TCP client disconnected"))
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
            if (isRunning) {
                logError("TCP服务器启动失败: ${e.message}", LogLevel.ERROR)
                notifyDisconnected(e)
            }
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
        logInfo("客户端已连接: ${clientSocket!!.remoteSocketAddress}", LogLevel.RUNTIME)
        
        _writer = PrintWriter(clientSocket!!.getOutputStream(), true)
        reader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
        
        notifyConnected()
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
            logInfo("关闭服务器Socket异常: ${e.message}", LogLevel.RUNTIME)
        }
        
        serverSocket = null
    }
    
    // ========== 消息处理 ==========
    
    private fun processReceivedMessage(line: String) {
        try {
            val jsonString = codec.extractJson(line)
            val displayJson = formatJsonForLog(jsonString)
            logInfo("接收协议: $displayJson", LogLevel.DEBUG)
            
            val message = codec.decode(line)
            handleReceivedMessage(message)
        } catch (e: Exception) {
            logError("消息解析失败: ${e.message}", LogLevel.ERROR)
        }
    }
    
    override fun send(message: LuaPandaMessage) {
        val encoded = codec.encode(message)

        logInfo("发送协议: ${formatJsonForLog(encoded.json)}", LogLevel.DEBUG)
        writePayload(encoded.payload)
    }
}

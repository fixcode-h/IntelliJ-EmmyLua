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

/**
 * 日志级别枚举
 */
enum class LogLevel(val value: Int, val description: String) {
    DEBUG(0, "调试日志"),      // 调试日志
    CONNECTION(1, "连接状态日志"), // 连接状态相关的日志
    ERROR(2, "错误日志")       // 错误日志
}

interface ILuaPandaTransportHandler {
    fun onReceiveMessage(message: LuaPandaMessage)
    fun onDisconnect()
    fun onConnect(success: Boolean)
}

abstract class LuaPandaTransporter(private val logger: DebugLogger? = null) {
    private var messageHandler: ((LuaPandaMessage) -> Unit)? = null
    private var connectionHandler: ((Boolean) -> Unit)? = null
    protected var callbackCounter = 0
    protected val callbacks = ConcurrentHashMap<String, (LuaPandaMessage) -> Unit>()
    private var logLevel: Int = LogLevel.CONNECTION.value // 默认日志级别为连接状态日志

    abstract fun start()
    abstract fun stop()
    abstract fun sendMessage(message: LuaPandaMessage)

    fun setLogLevel(level: Int) {
        this.logLevel = level
    }

    fun sendMessage(message: LuaPandaMessage, callback: (LuaPandaMessage?) -> Unit) {
        val callbackId = generateCallbackId()
        val messageWithCallback = LuaPandaMessage(message.cmd, message.info, callbackId, message.stack)
        registerCallback(callbackId) { response -> callback(response) }
        sendMessage(messageWithCallback)
    }

    /**
     * 向 Debugger 发消息 - 仿照VSCode插件的实现
     * @param cmd 发给Debugger的命令 'continue'/'stepover'/'stepin'/'stepout'/'restart'/'stop'
     * @param sendObject 消息参数，会被放置在协议的info中
     * @param callbackFunc 回调函数
     * @param timeOutSec 超时时间（秒）
     */
    fun commandToDebugger(cmd: String, sendObject: Any? = null, callbackFunc: ((LuaPandaMessage) -> Unit)? = null, timeOutSec: Int = 0) {
        val sendObj = mutableMapOf<String, Any>()
        
        // 有回调时才生成随机数callbackId
        if (callbackFunc != null) {
            val callbackId = generateUniqueCallbackId()
            
            // 注册回调
            registerCallback(callbackId, callbackFunc)
            
            // 设置超时（如果需要）
            if (timeOutSec > 0) {
                // TODO: 实现超时机制
            }
            
            sendObj["callbackId"] = callbackId
        }
        // 如果没有回调函数，则不设置callbackId字段
        
        sendObj["cmd"] = cmd
        if (sendObject != null) {
            sendObj["info"] = sendObject
        } else {
            sendObj["info"] = JsonObject() // 当sendObject是null时，info字段设为空的JsonObject
        }
        
        // 创建消息并发送
        val message = LuaPandaMessage(
            cmd = cmd,
            info = if (sendObject != null) Gson().toJsonTree(sendObject) else JsonObject(), // 确保info字段始终有值
            callbackId = sendObj["callbackId"] as? String ?: "0", // 没有回调时为空字符串
            stack = null
        )
        
        sendMessage(message)
    }

    fun setMessageHandler(handler: (LuaPandaMessage) -> Unit) {
        this.messageHandler = handler
    }

    fun setConnectionHandler(handler: (Boolean) -> Unit) {
        this.connectionHandler = handler
    }

    fun generateCallbackId(): String {
        return (++callbackCounter).toString()
    }

    /**
     * 生成唯一的回调ID - 仿照VSCode插件的实现
     */
    private fun generateUniqueCallbackId(): String {
        val max = 999999999
        val min = 10  // 10以内是保留位
        var isSame: Boolean
        var ranNum: Int
        
        do {
            isSame = false
            ranNum = (Math.random() * (max - min + 1) + min).toInt()
            
            // 检查随机数唯一性
            if (callbacks.containsKey(ranNum.toString())) {
                isSame = true
            }
        } while (isSame)
        
        return ranNum.toString()
    }

    fun registerCallback(callbackId: String, callback: (LuaPandaMessage) -> Unit) {
        callbacks[callbackId] = callback
    }

    /**
     * 根据日志级别打印信息日志
     * @param message 日志消息
     * @param level 日志级别
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

    protected fun handleReceivedMessage(message: LuaPandaMessage) {
        // 检查是否是回调消息
        val callback = callbacks.remove(message.callbackId)
        if (callback != null) {
            callback(message)
        } else {
            messageHandler?.invoke(message)
        }
    }

    protected fun notifyConnect(success: Boolean) {
        connectionHandler?.invoke(success)
    }

    protected fun notifyDisconnect() {
        connectionHandler?.invoke(false)
    }
}

class LuaPandaTcpClientTransporter(private val host: String, private val port: Int, logger: DebugLogger? = null) : LuaPandaTransporter(logger) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false

    override fun start() {
        logInfo("TCP客户端连接 $host:$port", LogLevel.CONNECTION) // 连接状态日志
        Thread {
            try {
                socket = Socket(host, port)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                isRunning = true
                
                notifyConnect(true)
                
                // 开始接收消息
                while (isRunning && !socket!!.isClosed) {
                    try {
                        val line = reader?.readLine()
                        if (line != null) {
                            try {
                                // 去掉协议分隔符 |*| 再解析JSON
                                val jsonString = line.removeSuffix("|*|")
                                // 将JSON字符串中的换行符转义以避免控制台空行
                                val displayJson = jsonString.replace("\n", "\\n").replace("\r", "\\r")
                                logInfo("接收协议: $displayJson", LogLevel.DEBUG) // 调试日志
                                val message = Gson().fromJson(jsonString, LuaPandaMessage::class.java)
                                handleReceivedMessage(message)
                            } catch (e: Exception) {
                                logError("消息解析失败: ${e.message}", LogLevel.ERROR) // 错误日志
                            }
                        } else {
                            // readLine返回null表示连接已断开
                            logInfo("检测到连接断开（readLine返回null）", LogLevel.CONNECTION)
                            break
                        }
                    } catch (e: Exception) {
                        // 读取异常也表示连接断开
                        logInfo("检测到连接断开（读取异常）: ${e.message}", LogLevel.CONNECTION)
                        break
                    }
                }
                
                // 连接断开后的清理和通知
                if (isRunning) {
                    logInfo("连接意外断开，通知调试进程", LogLevel.CONNECTION)
                    isRunning = false
                    notifyDisconnect()
                }
            } catch (e: Exception) {
                logError("TCP客户端连接失败: ${e.message}", LogLevel.CONNECTION) // 连接错误
                notifyConnect(false)
            }
        }.start()
    }

    override fun stop() {
        isRunning = false
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            logError("TCP客户端关闭异常: ${e.message}", LogLevel.CONNECTION) // 连接状态日志
        }
    }

    override fun sendMessage(message: LuaPandaMessage) {
        try {
            val json = Gson().toJson(message)
            // 确保协议格式符合 sendStr..TCPSplitChar.."\n" 的要求
            val finalMessage = "$json|*|"
            // 将JSON字符串中的换行符转义以避免控制台空行
            val displayJson = json.replace("\n", "\\n").replace("\r", "\\r")
            logInfo("发送协议: $displayJson", LogLevel.DEBUG) // 调试日志
            writer?.println(finalMessage)
        } catch (e: Exception) {
            logError("消息发送失败: ${e.message}", LogLevel.ERROR) // 错误日志
        }
    }
}

class LuaPandaTcpServerTransporter(private val port: Int, logger: DebugLogger? = null) : LuaPandaTransporter(logger) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false

    override fun start() {
        logInfo("TCP服务器监听端口 $port", LogLevel.CONNECTION)
        Thread {
            try {
                serverSocket = ServerSocket(port)
                clientSocket = serverSocket!!.accept()
                logInfo("客户端已连接: ${clientSocket!!.remoteSocketAddress}", LogLevel.CONNECTION)
                writer = PrintWriter(clientSocket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
                isRunning = true
                
                notifyConnect(true)
                
                // 开始接收消息
                while (isRunning && !clientSocket!!.isClosed) {
                    try {
                        val line = reader?.readLine()
                        if (line != null) {
                            try {
                                // 去掉协议分隔符 |*| 再解析JSON
                                val jsonString = line.removeSuffix("|*|")
                                // 将JSON字符串中的换行符转义以避免控制台空行
                                val displayJson = jsonString.replace("\n", "\\n").replace("\r", "\\r")
                                logInfo("接收协议: $displayJson", LogLevel.DEBUG)
                                val message = Gson().fromJson(jsonString, LuaPandaMessage::class.java)
                                handleReceivedMessage(message)
                            } catch (e: Exception) {
                                logError("消息解析失败: ${e.message}", LogLevel.ERROR)
                            }
                        } else {
                            // readLine返回null表示连接已断开
                            logInfo("检测到客户端断开连接（readLine返回null）", LogLevel.DEBUG)
                            break
                        }
                    } catch (e: Exception) {
                        // 读取异常也表示连接断开
                        logInfo("检测到客户端断开连接（读取异常）: ${e.message}", LogLevel.ERROR)
                        break
                    }
                }
                
                // 连接断开后的清理和通知
                if (isRunning) {
                    logInfo("客户端连接意外断开，通知调试进程", LogLevel.CONNECTION)
                    isRunning = false
                    notifyDisconnect()
                }
            } catch (e: Exception) {
                logError("TCP服务器启动失败: ${e.message}", LogLevel.ERROR)
                notifyConnect(false)
            }
        }.start()
    }

    override fun stop() {
        isRunning = false
        try {
            writer?.close()
            reader?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            logError("TCP服务器关闭异常: ${e.message}", LogLevel.CONNECTION)
        }
    }

    override fun sendMessage(message: LuaPandaMessage) {
        try {
            val json = Gson().toJson(message)
            // 确保协议格式符合 sendStr..TCPSplitChar.."\n" 的要求
            val finalMessage = "$json|*|"
            // 将JSON字符串中的换行符转义以避免控制台空行
            val displayJson = json.replace("\n", "\\n").replace("\r", "\\r")
            logInfo("发送协议: $displayJson", LogLevel.DEBUG)
            writer?.println(finalMessage)
        } catch (e: Exception) {
            logError("消息发送失败: ${e.message}", LogLevel.ERROR)
        }
    }
}
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
import com.intellij.execution.ui.ConsoleViewContentType
import com.tang.intellij.lua.debugger.DebugLogger
import com.tang.intellij.lua.debugger.LogConsoleType
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

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

    abstract fun start()
    abstract fun stop()
    abstract fun sendMessage(message: LuaPandaMessage)

    fun sendMessage(message: LuaPandaMessage, callback: (LuaPandaMessage?) -> Unit) {
        val callbackId = generateCallbackId()
        val messageWithCallback = LuaPandaMessage(message.cmd, message.info, callbackId, message.stack)
        registerCallback(callbackId, callback)
        sendMessage(messageWithCallback)
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

    fun registerCallback(callbackId: String, callback: (LuaPandaMessage) -> Unit) {
        callbacks[callbackId] = callback
    }

    protected fun logInfo(message: String) {
        logger?.println(message, LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT) ?: println(message)
    }

    protected fun logError(message: String) {
        logger?.println(message, LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT) ?: println(message)
    }

    protected fun handleReceivedMessage(message: LuaPandaMessage) {
        logInfo("[LuaPanda] 接收到消息: ${Gson().toJson(message)}")
        // 检查是否是回调消息
        val callback = callbacks.remove(message.callbackId)
        if (callback != null) {
            logInfo("[LuaPanda] 处理回调消息，回调ID: ${message.callbackId}")
            callback(message)
        } else {
            logInfo("[LuaPanda] 处理普通消息，命令: ${message.cmd}")
            messageHandler?.invoke(message)
        }
    }

    protected fun notifyConnect(success: Boolean) {
        if (success) {
            logInfo("[LuaPanda] 连接状态变更: 连接成功")
        } else {
            logError("[LuaPanda] 连接状态变更: 连接失败")
        }
        connectionHandler?.invoke(success)
    }

    protected fun notifyDisconnect() {
        logInfo("[LuaPanda] 连接状态变更: 连接断开")
        connectionHandler?.invoke(false)
    }
}

class LuaPandaTcpClientTransporter(private val host: String, private val port: Int, logger: DebugLogger? = null) : LuaPandaTransporter(logger) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false

    override fun start() {
        logInfo("[LuaPanda] TCP客户端开始连接到 $host:$port")
        Thread {
            try {
                socket = Socket(host, port)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                isRunning = true
                
                logInfo("[LuaPanda] TCP客户端连接成功")
                notifyConnect(true)
                
                // 开始接收消息
                while (isRunning && !socket!!.isClosed) {
                    val line = reader?.readLine()
                    if (line != null) {
                        logInfo("[LuaPanda] 接收到原始消息: $line")
                        try {
                            // 去掉协议分隔符 |*| 再解析JSON
                            val jsonString = line.removeSuffix("|*|")
                            val message = Gson().fromJson(jsonString, LuaPandaMessage::class.java)
                            handleReceivedMessage(message)
                        } catch (e: Exception) {
                            logError("[LuaPanda] 消息解析失败: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logError("[LuaPanda] TCP客户端连接失败: ${e.message}")
                notifyConnect(false)
            }
        }.start()
    }

    override fun stop() {
        logInfo("[LuaPanda] TCP客户端正在断开连接")
        isRunning = false
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            logError("[LuaPanda] TCP客户端关闭异常: ${e.message}")
        }
    }

    override fun sendMessage(message: LuaPandaMessage) {
        try {
            val json = Gson().toJson(message)
            // 确保协议格式符合 sendStr..TCPSplitChar.."\n" 的要求
            val finalMessage = "$json|*|"
            logInfo("[LuaPanda] 发送消息: $finalMessage")
            writer?.println(finalMessage)
        } catch (e: Exception) {
            logError("[LuaPanda] 消息发送失败: ${e.message}")
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
        logInfo("[LuaPanda] TCP服务器开始监听端口 $port")
        Thread {
            try {
                serverSocket = ServerSocket(port)
                logInfo("[LuaPanda] TCP服务器启动成功，等待客户端连接")
                clientSocket = serverSocket!!.accept()
                logInfo("[LuaPanda] TCP服务器接受客户端连接: ${clientSocket!!.remoteSocketAddress}")
                writer = PrintWriter(clientSocket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
                isRunning = true
                
                logInfo("[LuaPanda] TCP服务器连接建立成功")
                notifyConnect(true)
                
                // 开始接收消息
                while (isRunning && !clientSocket!!.isClosed) {
                    val line = reader?.readLine()
                    if (line != null) {
                        logInfo("[LuaPanda] 接收到原始消息: $line")
                        try {
                            // 去掉协议分隔符 |*| 再解析JSON
                            val jsonString = line.removeSuffix("|*|")
                            val message = Gson().fromJson(jsonString, LuaPandaMessage::class.java)
                            handleReceivedMessage(message)
                        } catch (e: Exception) {
                            logError("[LuaPanda] 消息解析失败: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logError("[LuaPanda] TCP服务器启动失败: ${e.message}")
                notifyConnect(false)
            }
        }.start()
    }

    override fun stop() {
        logInfo("[LuaPanda] TCP服务器正在关闭")
        isRunning = false
        try {
            writer?.close()
            reader?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            logError("[LuaPanda] TCP服务器关闭异常: ${e.message}")
        }
    }

    override fun sendMessage(message: LuaPandaMessage) {
        try {
            val json = Gson().toJson(message)
            // 确保协议格式符合 sendStr..TCPSplitChar.."\n" 的要求
            val finalMessage = "$json|*|"
            logInfo("[LuaPanda] 发送消息: $finalMessage")
            writer?.println(finalMessage)
        } catch (e: Exception) {
            logError("[LuaPanda] 消息发送失败: ${e.message}")
        }
    }
}
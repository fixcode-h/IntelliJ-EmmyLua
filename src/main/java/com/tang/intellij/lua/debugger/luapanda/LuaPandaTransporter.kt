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
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

interface ILuaPandaTransportHandler {
    fun onReceiveMessage(message: LuaPandaMessage)
    fun onDisconnect()
    fun onConnect(success: Boolean)
}

abstract class LuaPandaTransporter {
    private var messageHandler: ((LuaPandaMessage) -> Unit)? = null
    private var connectionHandler: ((Boolean) -> Unit)? = null
    protected var callbackCounter = 0
    protected val callbacks = ConcurrentHashMap<String, (LuaPandaMessage) -> Unit>()

    abstract fun start()
    abstract fun stop()
    abstract fun sendMessage(message: LuaPandaMessage)

    fun sendMessage(message: LuaPandaMessage, callback: (LuaPandaMessage?) -> Unit) {
        val callbackId = generateCallbackId()
        val messageWithCallback = LuaPandaMessage(message.cmd, message.info, callbackId)
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

class LuaPandaTcpClientTransporter(private val host: String, private val port: Int) : LuaPandaTransporter() {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false

    override fun start() {
        Thread {
            try {
                socket = Socket(host, port)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                isRunning = true
                
                notifyConnect(true)
                
                // 开始接收消息
                while (isRunning && !socket!!.isClosed) {
                    val line = reader?.readLine()
                    if (line != null) {
                        try {
                            val message = Gson().fromJson(line, LuaPandaMessage::class.java)
                            handleReceivedMessage(message)
                        } catch (e: Exception) {
                            // 忽略解析错误
                        }
                    }
                }
            } catch (e: Exception) {
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
            // 忽略关闭错误
        }
    }

    override fun sendMessage(message: LuaPandaMessage) {
        try {
            val json = Gson().toJson(message)
            writer?.println(json)
        } catch (e: Exception) {
            // 忽略发送错误
        }
    }
}

class LuaPandaTcpServerTransporter(private val port: Int) : LuaPandaTransporter() {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false

    override fun start() {
        Thread {
            try {
                serverSocket = ServerSocket(port)
                clientSocket = serverSocket!!.accept()
                writer = PrintWriter(clientSocket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
                isRunning = true
                
                notifyConnect(true)
                
                // 开始接收消息
                while (isRunning && !clientSocket!!.isClosed) {
                    val line = reader?.readLine()
                    if (line != null) {
                        try {
                            val message = Gson().fromJson(line, LuaPandaMessage::class.java)
                            handleReceivedMessage(message)
                        } catch (e: Exception) {
                            // 忽略解析错误
                        }
                    }
                }
            } catch (e: Exception) {
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
            // 忽略关闭错误
        }
    }

    override fun sendMessage(message: LuaPandaMessage) {
        try {
            val json = Gson().toJson(message)
            writer?.println(json)
        } catch (e: Exception) {
            // 忽略发送错误
        }
    }
}
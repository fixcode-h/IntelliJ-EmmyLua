package com.tang.intellij.lua.debugger.luapanda

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.tang.intellij.lua.debugger.LuaDebuggerEditorsProvider
import com.tang.intellij.lua.debugger.LuaLineBreakpointType
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * LuaPanda调试进程
 * 负责与LuaPanda调试器通信
 */
class LuaPandaDebugProcess(
    session: XDebugSession,
    private val configuration: LuaPandaRunConfiguration
) : XDebugProcess(session) {
    
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val commandId = AtomicInteger(1)
    private val pendingCommands = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val dummyProcessHandler = LuaPandaProcessHandler()
    
    override fun sessionInitialized() {
        super.sessionInitialized()
        startDebugServer()
    }
    
    override fun stop() {
        stopDebugServer()
        super.stop()
    }
    
    override fun resume(context: XSuspendContext?) {
        val command = buildJsonObject {
            put("cmd", "continue")
        }
        sendCommand(command, false)
    }
    
    override fun startStepOver(context: XSuspendContext?) {
        val command = buildJsonObject {
            put("cmd", "stepOver")
        }
        sendCommand(command, false)
    }
    
    override fun startStepInto(context: XSuspendContext?) {
        val command = buildJsonObject {
            put("cmd", "stepInto")
        }
        sendCommand(command, false)
    }
    
    override fun startStepOut(context: XSuspendContext?) {
        val command = buildJsonObject {
            put("cmd", "stepOut")
        }
        sendCommand(command, false)
    }
    
    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> {
        return arrayOf(LuaPandaLineBreakpointHandler())
    }
    
    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return LuaDebuggerEditorsProvider()
    }
    
    override fun createConsole(): ExecutionConsole {
        return session.consoleView
    }
    
    fun getDummyProcessHandler(): ProcessHandler {
        return dummyProcessHandler
    }
    
    private fun startDebugServer() {
        Thread {
            try {
                val port = configuration.debugPort
                serverSocket = ServerSocket(port)
                println("LuaPanda debug server started on port $port")
                
                clientSocket = serverSocket?.accept()
                println("LuaPanda debugger connected")
                
                clientSocket?.let { socket ->
                    writer = PrintWriter(socket.getOutputStream(), true)
                    reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    
                    // 开始监听消息
                    startMessageLoop()
                }
            } catch (e: Exception) {
                println("Failed to start debug server: ${e.message}")
            }
        }.start()
    }
    
    private fun stopDebugServer() {
        try {
            writer?.close()
            reader?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            println("Error stopping debug server: ${e.message}")
        }
    }
    
    private fun startMessageLoop() {
        Thread {
            try {
                reader?.let { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { processMessage(it) }
                    }
                }
            } catch (e: Exception) {
                println("Message loop error: ${e.message}")
            }
        }.start()
    }
    
    private fun processMessage(message: String) {
        try {
            val json = Json.parseToJsonElement(message).jsonObject
            val cmd = json["cmd"]?.jsonPrimitive?.content
            val id = json["id"]?.jsonPrimitive?.intOrNull
            
            when (cmd) {
                "stopped" -> {
                    ApplicationManager.getApplication().invokeLater {
                        val suspendContext = LuaPandaSuspendContext(this, json)
                        session.positionReached(suspendContext)
                    }
                }
                else -> {
                    // 处理命令响应
                    if (id != null) {
                        val future = pendingCommands.remove(id)
                        future?.complete(json)
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to process message: ${e.message}")
        }
    }
    
    fun sendCommand(command: JsonObject, waitForResponse: Boolean): CompletableFuture<JsonObject>? {
        val writer = this.writer ?: return null
        
        val id = commandId.getAndIncrement()
        val commandWithId = buildJsonObject {
            command.forEach { key, value -> put(key, value) }
            put("id", id)
        }
        
        val future = if (waitForResponse) {
            val future = CompletableFuture<JsonObject>()
            pendingCommands[id] = future
            future
        } else null
        
        writer.println(commandWithId.toString())
        return future
    }
    
    /**
     * LuaPanda断点处理器
     */
    inner class LuaPandaLineBreakpointHandler : XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(LuaLineBreakpointType::class.java) {
        
        override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
            val sourcePosition = breakpoint.sourcePosition ?: return
            val filePath = sourcePosition.file.path
            val line = sourcePosition.line + 1 // 转换为1基索引
            
            val command = buildJsonObject {
                put("cmd", "setBreakpoint")
                putJsonObject("info") {
                    put("path", filePath)
                    put("line", line)
                }
            }
            
            sendCommand(command, false)
        }
        
        override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>, temporary: Boolean) {
            val sourcePosition = breakpoint.sourcePosition ?: return
            val filePath = sourcePosition.file.path
            val line = sourcePosition.line + 1 // 转换为1基索引
            
            val command = buildJsonObject {
                put("cmd", "removeBreakpoint")
                putJsonObject("info") {
                    put("path", filePath)
                    put("line", line)
                }
            }
            
            sendCommand(command, false)
        }
    }
}

/**
 * LuaPanda进程处理器
 * 用于满足XDebugProcess的ProcessHandler要求
 */
class LuaPandaProcessHandler : ProcessHandler() {
    
    override fun destroyProcessImpl() {
        // LuaPanda调试不需要销毁进程
        notifyProcessTerminated(0)
    }
    
    override fun detachProcessImpl() {
        // LuaPanda调试不需要分离进程
        notifyProcessDetached()
    }
    
    override fun detachIsDefault(): Boolean = false
    
    override fun getProcessInput(): java.io.OutputStream? = null
}
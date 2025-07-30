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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
        ApplicationManager.getApplication().executeOnPooledThread {
            setupTransporter()
        }
    }

    private fun setupTransporter() {
        transporter = when (configuration.transportType) {
            LuaPandaTransportType.TCP_CLIENT -> LuaPandaTcpClientTransporter(configuration.host, configuration.port)
            LuaPandaTransportType.TCP_SERVER -> LuaPandaTcpServerTransporter(configuration.port)
        }
        
        transporter?.setMessageHandler { message ->
            handleMessage(message)
        }
        
        transporter?.setConnectionHandler { connected ->
            if (connected) {
                onConnect()
            } else {
                onDisconnect()
            }
        }
        
        try {
            transporter?.start()
        } catch (e: Exception) {
            logger.error("Failed to start transporter", e)
            onDisconnect()
        }
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    private fun onConnect() {
        // 发送初始化消息
        val initInfo = LuaPandaInitInfo(
            stopOnEntry = configuration.stopOnEntry.toString(),
            useCHook = configuration.useCHook.toString(),
            logLevel = configuration.logLevel.toString()
        )
        val initMessage = LuaPandaMessage(LuaPandaCommands.INIT_SUCCESS, Gson().toJsonTree(initInfo).asJsonObject, "0")
        transporter?.sendMessage(initMessage)
        
        // 发送现有断点
        val breakpoints = XDebuggerManager.getInstance(session.project)
            .breakpointManager
            .getBreakpoints(LuaLineBreakpointType::class.java)
        breakpoints.forEach { breakpoint ->
            breakpoint.sourcePosition?.let { position ->
                registerBreakpoint(position, breakpoint)
            }
        }
    }

    private fun onDisconnect() {
        stop()
        session?.stop()
    }

    private fun handleMessage(message: LuaPandaMessage) {
        when (message.cmd) {
            LuaPandaCommands.STOP_ON_BREAKPOINT -> {
                val stacks = Gson().fromJson(message.info, Array<LuaPandaStack>::class.java).toList()
                onBreak(stacks)
            }
            LuaPandaCommands.OUTPUT -> {
                val outputText = message.info?.get("content")?.asString ?: ""
                println(outputText, LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            }
            else -> {
                logger.info("Unknown message: ${message.cmd}")
            }
        }
    }

    private fun onBreak(stacks: List<LuaPandaStack>) {
        if (stacks.isNotEmpty()) {
            val suspendContext = LuaPandaSuspendContext(this, stacks)
            ApplicationManager.getApplication().invokeLater {
                session.positionReached(suspendContext)
                session.showExecutionPoint()
            }
        }
    }

    override fun registerBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val filePath = sourcePosition.file.canonicalPath ?: sourcePosition.file.path
        
        val newId = idCounter++
        breakpoint.putUserData(ID, newId)
        
        val luaPandaBreakpoint = LuaPandaBreakpoint(
            line = breakpoint.line + 1, // Convert to 1-based
            condition = breakpoint.conditionExpression?.expression,
            logMessage = if (breakpoint.isLogMessage) breakpoint.logExpressionObject?.expression else null
        )
        
        breakpoints[newId] = luaPandaBreakpoint
        
        val message = LuaPandaMessage(LuaPandaCommands.SET_BREAKPOINT, Gson().toJsonTree(luaPandaBreakpoint).asJsonObject, "0")
        transporter?.sendMessage(message)
    }

    override fun unregisterBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val id = breakpoint.getUserData(ID)
        val luaPandaBreakpoint = breakpoints[id]
        if (luaPandaBreakpoint != null) {
            breakpoints.remove(id)
            val message = LuaPandaMessage(LuaPandaCommands.SET_BREAKPOINT, Gson().toJsonTree(luaPandaBreakpoint).asJsonObject, "0")
            transporter?.sendMessage(message)
        }
    }

    override fun startPausing() {
        val message = LuaPandaMessage(LuaPandaCommands.STOP_ON_BREAKPOINT, null, "0")
        transporter?.sendMessage(message)
    }

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        val tempBreakpoint = LuaPandaBreakpoint(
            line = position.line + 1,
            condition = null,
            logMessage = null
        )
        val message = LuaPandaMessage(LuaPandaCommands.SET_BREAKPOINT, Gson().toJsonTree(tempBreakpoint).asJsonObject, "0")
        transporter?.sendMessage(message)
    }

    override fun run() {
        val message = LuaPandaMessage(LuaPandaCommands.CONTINUE, null, "0")
        transporter?.sendMessage(message)
    }

    override fun stop() {
        val message = LuaPandaMessage(LuaPandaCommands.STOP_RUN, null, "0")
        transporter?.sendMessage(message)
        transporter?.stop()
        transporter = null
    }

    override fun startStepOver(context: XSuspendContext?) {
        val message = LuaPandaMessage(LuaPandaCommands.STEP_OVER, null, "0")
        transporter?.sendMessage(message)
    }

    override fun startStepInto(context: XSuspendContext?) {
        val message = LuaPandaMessage(LuaPandaCommands.STEP_IN, null, "0")
        transporter?.sendMessage(message)
    }

    override fun startStepOut(context: XSuspendContext?) {
        val message = LuaPandaMessage(LuaPandaCommands.STEP_OUT, null, "0")
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
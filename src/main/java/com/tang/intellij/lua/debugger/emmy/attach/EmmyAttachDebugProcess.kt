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

package com.tang.intellij.lua.debugger.emmy.attach

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.xdebugger.XDebugSession
import com.tang.intellij.lua.debugger.emmy.*
import com.tang.intellij.lua.debugger.LogConsoleType
import com.tang.intellij.lua.psi.LuaFileUtil
import java.io.File
import java.nio.charset.Charset

/**
 * Emmy附加调试进程
 */
class EmmyAttachDebugProcess(session: XDebugSession) : EmmyDebugProcessBase(session) {
    
    private val configuration = session.runProfile as EmmyAttachDebugConfiguration
    private var isAttached = false
    private var attachedPid = 0
    private var usedArch: WinArch = WinArch.X86
    @Volatile private var isStopping = false  // 添加停止标志

    override fun setupTransporter() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 执行附加操作
                attachToProcess()
                
                                                  // 等待DLL注入完成
                 Thread.sleep(2000)
                 
                                 // 实现重试连接机制，因为DLL可能需要时间启动调试服务器
                val port = ProcessUtils.getPortFromPid(configuration.pid)
                println("🔌 尝试连接调试端口: $port", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                
                // 首先快速检测端口是否可连接（尝试IPv4和IPv6）
                val hosts = listOf("127.0.0.1", "::1", "localhost")
                var anyPortOpen = false
                
                for (host in hosts) {
                    if (ProcessUtils.isPortConnectable(host, port, 1000)) {
                        println("🔍 检测到调试服务器在 $host:$port 上运行", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                        anyPortOpen = true
                        break
                    }
                }
                
                if (!anyPortOpen) {
                    println("⚠️ 调试端口 $port 在所有地址上都不可访问，这可能表明:", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
                    println("   1) 调试服务器尚未启动", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
                    println("   2) DLL注入失败或仍在初始化中", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
                    println("   3) 端口被防火墙阻止", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
                }
                
                var connected = false
                var lastException: Exception? = null
                
                // 重试连接最多30秒（每2秒重试一次）
                var attempt = 1
                while (attempt <= 15 && !isStopping) {
                    try {
                        println("🔄 连接尝试 $attempt/15...", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                        
                        // 尝试多个地址：IPv4 和 IPv6
                        val hosts = listOf("127.0.0.1", "::1", "localhost")
                        var connectionSuccess = false
                        
                        for (host in hosts) {
                            try {
                                println("   🔌 尝试连接 $host:$port", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                                
                                val transporter = SocketClientTransporter(host, port)
                                transporter.handler = this
                                transporter.logger = this
                                this.transporter = transporter
                                
                                transporter.start()
                                connected = true
                                connectionSuccess = true
                                println("🎉 Connected! 调试器已连接成功 ($host:$port)", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                                break
                                
                            } catch (hostException: Exception) {
                                // 记录但继续尝试下一个地址
                                println("   ❌ $host:$port 连接失败: ${hostException.message?.take(30) ?: "未知错误"}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                                lastException = hostException
                            }
                        }
                        
                        if (connectionSuccess) {
                            break
                        }
                        
                    } catch (e: Exception) {
                        lastException = e
                        val shortMessage = e.message?.let { msg ->
                            when {
                                msg.contains("Connection refused") -> "连接被拒绝"
                                msg.contains("ConnectException") -> "无法连接"
                                msg.contains("timeout") -> "连接超时"
                                else -> msg.take(50)
                            }
                        } ?: "未知错误"
                        
                        println("❌ 所有地址连接失败: $shortMessage，等待2秒后重试...", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                    }
                    
                    if (!connected) {
                        // 分段睡眠，以便及时响应停止信号
                        var i = 0
                        while (i < 20 && !isStopping) {
                            Thread.sleep(100) // 总共2秒，但每100ms检查一次停止标志
                            i++
                        }
                    }
                    attempt++
                }
                 
                 // 如果因为停止而退出循环
                 if (isStopping) {
                     println("🛑 调试会话已停止，取消连接重试", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                     return@executeOnPooledThread
                 }
                 
                                 if (!connected) {
                    val errorDetail = lastException?.message ?: "未知错误"
                    val errorMessage = StringBuilder().apply {
                        appendLine("❌ 经过15次重试仍无法连接到调试端口 $port")
                        appendLine("🔍 可能的原因分析：")
                        appendLine("   1️⃣ 目标进程不包含Lua运行时 - 这是最常见的原因")
                        appendLine("   2️⃣ DLL注入失败或被防病毒软件阻止")
                        appendLine("   3️⃣ 调试端口被其他程序占用")
                        appendLine("   4️⃣ 防火墙阻止了本地连接")
                        appendLine("🛠️ 建议解决方案：")
                        appendLine("   📋 检查目标进程是否真正使用了Lua")
                        appendLine("   🔒 临时禁用防病毒软件和防火墙")
                        appendLine("   🎯 尝试选择包含Lua.dll的进程")
                        appendLine("💡 最后错误: $errorDetail")
                    }.toString()
                    throw Exception(errorMessage)
                }
                
            } catch (e: Exception) {
                this.error("附加调试失败: ${e.message}")
                this.onDisconnect()
            }
        }
    }

    /**
     * 附加到目标进程
     */
         private fun attachToProcess() {
         if (!SystemInfoRt.isWindows) {
             throw Exception("附加调试目前仅支持Windows系统")
         }

                  // 验证调试器工具
         val validationError = DebuggerPathUtils.validateDebuggerTools()
         if (validationError != null) {
             throw Exception(validationError)
         }

         val debuggerPath = DebuggerPathUtils.getEmmyDebuggerPath()!!
         println("调试器工具路径: $debuggerPath", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)

         // 检测进程架构
         val detectedArch = ProcessUtils.detectProcessArch(configuration.pid)
        val targetArch = configuration.winArch.toWinArch()
        
        // 如果配置的架构与检测的架构不一致，使用检测的架构
        val useArch = if (detectedArch != targetArch) {
            println("检测到进程架构为${detectedArch}，但配置为${targetArch}，将使用检测的架构")
            detectedArch
        } else {
            targetArch
        }

                 val toolPath = DebuggerPathUtils.getEmmyToolPath(useArch)
             ?: throw Exception("找不到${useArch}架构的调试工具(emmy_tool.exe)")
         
         val dllPath = DebuggerPathUtils.getEmmyHookPath(useArch)
             ?: throw Exception("找不到${useArch}架构的调试库(emmy_hook.dll)")

         val toolDir = File(toolPath).parentFile

                 // 构建附加命令（使用绝对路径确保能找到文件）
         val commands = mutableListOf(
             toolPath,  // 使用绝对路径
             "attach",
             "-p",
             configuration.pid.toString(),
             "-dir",
             "\"${toolDir.absolutePath}\"",
             "-dll",
             "emmy_hook.dll"
         )

        // 添加日志捕获选项
        if (configuration.captureLog) {
            commands.add("-capture-log")
        }

                 println("执行附加命令: ${commands.joinToString(" ")}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
         println("工作目录: ${toolDir.absolutePath}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)

         // 执行附加命令（与VSCode完全相同的方式）
         val processBuilder = ProcessBuilder(commands)
         processBuilder.directory(toolDir)
         val process = processBuilder.start()
         
         // 捕获stdout输出
         val outputThread = Thread {
             try {
                 process.inputStream.bufferedReader().use { reader ->
                     var line: String?
                     while (reader.readLine().also { line = it } != null) {
                         println("📤 attach输出: $line", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                     }
                 }
             } catch (e: Exception) {
                 println("📤 输出读取异常: ${e.message}", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
             }
         }
         
         // 捕获stderr输出
         val errorThread = Thread {
             try {
                 process.errorStream.bufferedReader().use { reader ->
                     var line: String?
                     while (reader.readLine().also { line = it } != null) {
                         println("📤 attach错误: $line", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
                     }
                 }
             } catch (e: Exception) {
                 println("📤 错误输出读取异常: ${e.message}", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
             }
         }
         
         outputThread.start()
         errorThread.start()

                 // 等待attach进程完成
         val exitCode = process.waitFor()
         outputThread.join(10000) // 等待输出线程完成，最多10秒
         errorThread.join(5000)   // 等待错误输出线程完成
         
         if (exitCode != 0) {
             val errorOutput = process.errorStream.readBytes()
             val errorStr = String(errorOutput, Charset.defaultCharset())
             throw Exception("附加失败(退出码: $exitCode): $errorStr")
         }

                 isAttached = true
         attachedPid = configuration.pid
         usedArch = useArch
         println("✅ 成功附加到进程 ${configuration.pid}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
         println("📝 注意: 目标进程必须包含Lua运行时才能建立调试连接", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)

                         // 如果启用了日志捕获，启动日志捕获进程（模拟VSCode的receive_log功能）
        if (configuration.captureLog) {
            startLogCapture(toolDir)
        }
        
        // 分析目标进程模块，检测Lua运行时
        println("🔍 正在分析目标进程模块...", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        val moduleAnalysis = ProcessUtils.analyzeProcessModules(configuration.pid, useArch)
        
        if (moduleAnalysis.errorMessage != null) {
            println("⚠️ 模块分析遇到问题: ${moduleAnalysis.errorMessage}", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
        }
        
        if (moduleAnalysis.hasLuaRuntime) {
            println("✅ 检测到Lua运行时模块:", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            moduleAnalysis.luaModules.forEach { module ->
                println("  📚 $module", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            }
        } else {
            val totalModules = moduleAnalysis.allModules.size
            println("⚠️ 未检测到Lua运行时模块！", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
            println("📊 进程共包含 $totalModules 个模块", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            
            if (totalModules > 0) {
                println("🔍 已分析的模块包括:", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                moduleAnalysis.allModules.take(10).forEach { module ->  // 只显示前10个
                    println("  📄 $module", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                }
                if (totalModules > 10) {
                    println("  ... 还有 ${totalModules - 10} 个模块", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                }
            }
            
            println("💡 建议：确保目标进程包含Lua运行时(如lua.dll、luajit.dll等)", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        }
    }

    /**
     * 启动日志捕获
     */
    private fun startLogCapture(toolDir: File) {
        try {
            val captureCommands = listOf(
                "wt", // Windows Terminal
                File(toolDir, "emmy_tool.exe").absolutePath,
                "receive_log",
                "-p", configuration.pid.toString()
            )

            val processBuilder = ProcessBuilder(captureCommands)
            processBuilder.directory(toolDir)
            processBuilder.start()

            println("启动日志捕获进程", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        } catch (e: Exception) {
            println("启动日志捕获失败: ${e.message}", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
        }
    }

    override fun onConnect(suc: Boolean) {
        if (suc) {
            println("🔗 TCP连接已建立", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            println("📤 正在发送初始化请求...", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            
            // 按照VSCode流程：连接成功后直接发送初始化请求
            ApplicationManager.getApplication().runReadAction {
                sendInitReq()
            }
        } else {
            this.error("❌ 调试器连接失败")
            stop()
        }
    }

    override fun onDisconnect() {
        println("📡 调试器连接断开", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        stop()
        session?.stop()
    }

    override fun onReceiveMessage(cmd: MessageCMD, json: String) {
        when (cmd) {
            MessageCMD.AttachedNotify -> {
                try {
                    val gson = com.google.gson.Gson()
                    val msg = gson.fromJson(json, AttachedNotify::class.java)
                    println("🎯 Connected! 已成功附加到Lua状态 0x${msg.state.toString(16)}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                    println("🚀 调试器现已就绪，可以开始调试", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                } catch (e: Exception) {
                    println("🎯 Connected! 已成功附加到Lua状态", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                    println("🚀 调试器现已就绪，可以开始调试", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                }
            }
            MessageCMD.LogNotify -> {
                try {
                    val gson = com.google.gson.Gson()
                    val msg = gson.fromJson(json, LogNotify::class.java)
                    println("📝 ${msg.message}", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
                } catch (e: Exception) {
                    // 忽略解析错误
                }
            }
            else -> {
                // 调用父类处理其他消息
                super.onReceiveMessage(cmd, json)
            }
        }
    }

    override fun stop() {
        // 设置停止标志，停止所有后台操作
        isStopping = true
        
        // 先调用父类停止，确保调试会话正确结束
        super.stop()
        
        // 异步执行清理，避免阻塞调试停止
        if (isAttached && attachedPid > 0) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    detachFromProcess()
                } catch (e: Exception) {
                    // 清理失败不影响调试停止
                }
            }
        }
    }

    /**
     * 清理调试资源
     */
    private fun detachFromProcess() {
        try {
            println("🔄 正在清理调试会话资源...", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            
            // 1. 断开TCP连接
            transporter?.let { 
                try {
                    it.close()
                } catch (e: Exception) {
                    // 忽略关闭异常
                }
            }
            
            // 2. 等待一段时间让目标进程清理资源
            Thread.sleep(1000)
            
            println("✅ 调试会话清理完成", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            println("📝 注意: DLL文件可能仍被目标进程占用，这是正常现象", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            
        } catch (e: Exception) {
            println("⚠️ 清理过程出错: ${e.message}", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
        } finally {
            isAttached = false
            attachedPid = 0
        }
    }

    /**
     * 发送初始化请求（按照VSCode流程）
     */
    private fun sendInitReq() {
        println("📤 发送调试器初始化请求", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        
        // 1. 发送初始化脚本（emmyHelper.lua）
        val helperPath = LuaFileUtil.getPluginVirtualFile("debugger/emmy/emmyHelper.lua")
        if (helperPath != null) {
            val code = File(helperPath).readText()
            val extensions = com.tang.intellij.lua.psi.LuaFileManager.extensions
            transporter?.send(InitMessage(code, extensions))
            println("📤 发送InitReq消息（emmyHelper.lua）", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        }
        
        // 2. 发送断点信息
        val breakpointManager = com.intellij.xdebugger.XDebuggerManager.getInstance(session.project).breakpointManager
        val breakpoints = breakpointManager.getBreakpoints(com.tang.intellij.lua.debugger.LuaLineBreakpointType::class.java)
        if (breakpoints.isNotEmpty()) {
            println("📤 发送 ${breakpoints.size} 个断点", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            breakpoints.forEach { breakpoint ->
                breakpoint.sourcePosition?.let { position ->
                    registerBreakpoint(position, breakpoint)
                }
            }
        }
        
        // 3. 发送准备消息
        transporter?.send(Message(MessageCMD.ReadyReq))
        println("📤 发送ReadyReq消息", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
    }
}

/**
 * 附加通知消息
 */
data class AttachedNotify(val state: Long) 
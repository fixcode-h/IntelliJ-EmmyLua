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
import com.tang.intellij.lua.project.LuaSettings
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
    // 移除进程监控，改为依赖Socket连接状态检测
    
    /**
     * 根据日志等级输出日志
     */
    private fun logWithLevel(message: String, level: LogLevel, contentType: ConsoleViewContentType = ConsoleViewContentType.SYSTEM_OUTPUT) {
        if (level.level >= configuration.logLevel.level) {
            println(message, LogConsoleType.NORMAL, contentType)
        }
    }

    override fun setupTransporter() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 执行附加操作
                attachToProcess()
                
                // 等待DLL注入完成（缩短等待时间）
                Thread.sleep(100)
                
                // 获取调试端口并尝试连接
                val port = ProcessUtils.getPortFromPid(this.attachedPid)
                logWithLevel("🔌 尝试连接调试端口: $port", LogLevel.NORMAL)
                
                // 尝试多个地址：IPv4 和 IPv6
                val hosts = listOf("127.0.0.1", "::1", "localhost")
                var connected = false
                var lastException: Exception? = null
                
                for (host in hosts) {
                    try {
                        logWithLevel("🔌 尝试连接 $host:$port", LogLevel.NORMAL)
                        
                        val transporter = SocketClientTransporter(host, port)
                        transporter.handler = this
                        transporter.logger = this
                        this.transporter = transporter
                        
                        transporter.start()
                        connected = true
                        logWithLevel("🎉 Connected! 调试器已连接成功 ($host:$port)", LogLevel.NORMAL)
                        break
                        
                    } catch (hostException: Exception) {
                        val errorMsg = when {
                            hostException.message?.contains("Connection refused") == true -> "连接被拒绝"
                            hostException.message?.contains("ConnectException") == true -> "无法连接"
                            hostException.message?.contains("timeout") == true -> "连接超时"
                            else -> hostException.message?.take(30) ?: "未知错误"
                        }
                        logWithLevel("❌ $host:$port 连接失败: $errorMsg", LogLevel.ERROR)
                        lastException = hostException
                    }
                }
                
                if (!connected) {
                    val errorDetail = lastException?.message ?: "未知错误"
                    val errorMessage = "❌ 无法连接到调试端口 $port\n" +
                            "🔍 可能原因：DLL注入失败、调试服务器未启动或端口被阻止\n" +
                            "💻 最后错误：$errorDetail"
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
         logWithLevel("调试器工具路径: $debuggerPath", LogLevel.DEBUG)

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

                 logWithLevel("执行附加命令: ${commands.joinToString(" ")}", LogLevel.DEBUG)
         logWithLevel("工作目录: ${toolDir.absolutePath}", LogLevel.DEBUG)

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
                         logWithLevel("📤 attach输出: $line", LogLevel.DEBUG)
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
                         // 检查是否为inject dll相关的正常信息
                         println("📤 attach信息: $line", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
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
         outputThread.join(3000) // 等待输出线程完成，最多3秒
         errorThread.join(2000)   // 等待错误输出线程完成
         
         if (exitCode != 0) {
             val errorOutput = process.errorStream.readBytes()
             val errorStr = String(errorOutput, Charset.defaultCharset())
             throw Exception("附加失败(退出码: $exitCode): $errorStr")
         }

                 isAttached = true
         attachedPid = configuration.pid
         usedArch = useArch
         logWithLevel("✅ 成功附加到进程 ${configuration.pid}", LogLevel.NORMAL)
         logWithLevel("📝 注意: 目标进程必须包含Lua运行时才能建立调试连接", LogLevel.DEBUG)

                         // 如果启用了日志捕获，启动日志捕获进程（模拟VSCode的receive_log功能）
        if (configuration.captureLog) {
            startLogCapture(toolDir)
        }
        
        // 分析目标进程模块，检测Lua运行时
        logWithLevel("🔍 正在分析目标进程模块...", LogLevel.DEBUG)
        val moduleAnalysis = ProcessUtils.analyzeProcessModules(configuration.pid, useArch)
        
        if (moduleAnalysis.errorMessage != null) {
            logWithLevel("⚠️ 模块分析遇到问题: ${moduleAnalysis.errorMessage}", LogLevel.DEBUG)
        }
        
        if (moduleAnalysis.hasLuaRuntime) {
            logWithLevel("✅ 检测到标准Lua运行时模块:", LogLevel.DEBUG)
             moduleAnalysis.luaModules.forEach { module ->
                 logWithLevel("  📚 $module", LogLevel.DEBUG)
             }
             logWithLevel("🚀 调试器将尝试连接到这些Lua运行时", LogLevel.DEBUG)
        } else {
            val totalModules = moduleAnalysis.allModules.size
            logWithLevel("⚠️ 未检测到标准Lua运行时模块！", LogLevel.DEBUG)
            logWithLevel("📊 进程共包含 $totalModules 个模块", LogLevel.DEBUG)
            
            if (totalModules > 0) {
                logWithLevel("🔍 已分析的模块包括:", LogLevel.DEBUG)
             moduleAnalysis.allModules.take(10).forEach { module ->
                 logWithLevel("  📄 $module", LogLevel.DEBUG)
             }
             if (totalModules > 10) {
                 logWithLevel("  ... 还有 ${totalModules - 10} 个模块", LogLevel.DEBUG)
             }
            }
            
            logWithLevel("💡 建议：确保目标进程包含标准Lua运行时(如lua.dll、lua51.dll、luajit.dll等)", LogLevel.DEBUG)
            logWithLevel("⚠️ 警告：由于未检测到标准Lua运行时，调试连接可能会失败", LogLevel.DEBUG)
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

            logWithLevel("启动日志捕获进程", LogLevel.DEBUG)
        } catch (e: Exception) {
            logWithLevel("启动日志捕获失败: ${e.message}", LogLevel.ERROR)
        }
    }

    override fun onConnect(suc: Boolean) {
        if (suc) {
            // 移除简单的TCP连接消息，保留初始化请求信息
        logWithLevel("📤 正在发送初始化请求...", LogLevel.DEBUG)
            
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
        logWithLevel("📡 调试器连接断开", LogLevel.NORMAL)
        
        // 参考VSCode版本的实现：连接断开时自动停止调试会话
        if (!isStopping) {
            logWithLevel("🛑 检测到客户端断开连接，正在停止调试会话...", LogLevel.NORMAL)
            
            // 在UI线程中停止调试会话，避免线程安全问题
            ApplicationManager.getApplication().invokeLater {
                try {
                    session?.stop()
                    logWithLevel("✅ 调试会话已停止", LogLevel.NORMAL)
                } catch (e: Exception) {
                    logWithLevel("⚠️ 停止调试会话时出错: ${e.message}", LogLevel.DEBUG)
                }
            }
        }
    }

    override fun onReceiveMessage(cmd: MessageCMD, json: String) {
        when (cmd) {
            MessageCMD.AttachedNotify -> {
                try {
                    val gson = com.google.gson.Gson()
                    val msg = gson.fromJson(json, AttachedNotify::class.java)
                    logWithLevel("🎯 Connected! 已成功附加到Lua状态 0x${msg.state.toString(16)}", LogLevel.NORMAL)
            logWithLevel("🚀 调试器现已就绪，可以开始调试", LogLevel.NORMAL)
                } catch (e: Exception) {
                    logWithLevel("🎯 Connected! 已成功附加到Lua状态", LogLevel.NORMAL)
            logWithLevel("🚀 调试器现已就绪，可以开始调试", LogLevel.NORMAL)
                }
            }
            MessageCMD.LogNotify -> {
                try {
                    val gson = com.google.gson.Gson()
                    val msg = gson.fromJson(json, LogNotify::class.java)
                    logWithLevel("📝 ${msg.message}", LogLevel.DEBUG)
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
            logWithLevel("🔄 正在清理调试会话资源...", LogLevel.DEBUG)
            
            // 1. 断开TCP连接
            transporter?.let { 
                try {
                    it.close()
                } catch (e: Exception) {
                    // 忽略关闭异常
                }
            }
            
            // 2. 等待一段时间让目标进程清理资源（缩短等待时间）
            Thread.sleep(300)
            
            logWithLevel("✅ 调试会话清理完成", LogLevel.NORMAL)
        logWithLevel("📝 注意: DLL文件可能仍被目标进程占用，这是正常现象", LogLevel.NORMAL)
            
        } catch (e: Exception) {
            println("⚠️ 清理过程出错: ${e.message}", LogConsoleType.NORMAL, ConsoleViewContentType.ERROR_OUTPUT)
        } finally {
            isAttached = false
            attachedPid = 0
        }
    }

    /**
     * 读取插件资源文件内容，支持JAR包和文件系统
     */
    private fun readPluginResource(path: String): String? {
        return try {
            // 如果是emmyHelper.lua文件，优先使用用户设置的自定义路径
            if (path == "debugger/emmy/emmyHelper.lua") {
                val settings = LuaSettings.instance
                val customPath = settings.customEmmyHelperPath
                if (!customPath.isNullOrBlank()) {
                    val customFile = File(customPath)
                    if (customFile.exists() && customFile.isFile()) {
                        val content = customFile.readText()
                        logWithLevel("✅ 成功从自定义路径读取EmmyHelper: $customPath", LogLevel.DEBUG)
                        logWithLevel("📝 内容长度: ${content.length} 字符", LogLevel.DEBUG)
                        logWithLevel("📋 内容预览: ${content.take(200)}...", LogLevel.DEBUG)
                        return content
                    } else {
                        logWithLevel("⚠️ 自定义EmmyHelper路径无效，回退到默认路径: $customPath", LogLevel.DEBUG)
                    }
                }
            }
            
            // 首先尝试使用类加载器从JAR包中读取
            val classLoader = LuaFileUtil::class.java.classLoader
            val resource = classLoader.getResource(path)
            if (resource != null) {
                val content = resource.readText()
                logWithLevel("✅ 成功从类加载器读取资源: $path", LogLevel.DEBUG)
                logWithLevel("📄 资源URL: ${resource}", LogLevel.DEBUG)
                logWithLevel("📝 内容长度: ${content.length} 字符", LogLevel.DEBUG)
                logWithLevel("📋 内容预览: ${content.take(200)}...", LogLevel.DEBUG)
                content
            } else {
                // 如果类加载器无法找到，尝试使用LuaFileUtil的方法
                val filePath = LuaFileUtil.getPluginVirtualFile(path)
                if (filePath != null && !filePath.startsWith("jar:")) {
                    val content = File(filePath).readText()
                    logWithLevel("✅ 成功从文件系统读取资源: $filePath", LogLevel.DEBUG)
                    logWithLevel("📝 内容长度: ${content.length} 字符", LogLevel.DEBUG)
                    logWithLevel("📋 内容预览: ${content.take(200)}...", LogLevel.DEBUG)
                    content
                } else {
                    logWithLevel("❌ 无法找到资源: $path", LogLevel.ERROR, ConsoleViewContentType.ERROR_OUTPUT)
                    logWithLevel("🔍 LuaFileUtil返回路径: $filePath", LogLevel.DEBUG)
                    null
                }
            }
        } catch (e: Exception) {
            logWithLevel("❌ 读取插件资源失败: $path, ${e.message}", LogLevel.ERROR, ConsoleViewContentType.ERROR_OUTPUT)
            null
        }
    }

    /**
     * 发送初始化请求（按照VSCode流程）
     */
    private fun sendInitReq() {
        logWithLevel("📤 发送调试器初始化请求", LogLevel.DEBUG)
        
        // 在后台线程执行文件读取操作，避免EDT违规
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 1. 发送初始化脚本（emmyHelper.lua）
                val code = readPluginResource("debugger/emmy/emmyHelper.lua")
                if (code != null) {
                    val extensions = com.tang.intellij.lua.psi.LuaFileManager.extensions
                    transporter?.send(InitMessage(code, extensions))
                    logWithLevel("📤 发送InitReq消息（emmyHelper.lua）", LogLevel.DEBUG)
                } else {
                    logWithLevel("❌ 无法读取emmyHelper.lua文件", LogLevel.ERROR, ConsoleViewContentType.ERROR_OUTPUT)
                }
                
                // 2. 发送断点信息
                val breakpointManager = com.intellij.xdebugger.XDebuggerManager.getInstance(session.project).breakpointManager
                val breakpoints = breakpointManager.getBreakpoints(com.tang.intellij.lua.debugger.LuaLineBreakpointType::class.java)
                if (breakpoints.isNotEmpty()) {
                    logWithLevel("📤 发送 ${breakpoints.size} 个断点", LogLevel.DEBUG)
                    breakpoints.forEach { breakpoint ->
                        breakpoint.sourcePosition?.let { position ->
                            registerBreakpoint(position, breakpoint)
                        }
                    }
                }
                
                // 3. 发送准备消息
                transporter?.send(Message(MessageCMD.ReadyReq))
                logWithLevel("📤 发送ReadyReq消息", LogLevel.DEBUG)
                
            } catch (e: Exception) {
                logWithLevel("❌ 发送初始化请求失败: ${e.message}", LogLevel.ERROR)
                this@EmmyAttachDebugProcess.error("初始化请求失败: ${e.message}")
            }
        }
    }
}

/**
 * 附加通知消息
 */
data class AttachedNotify(val state: Long)
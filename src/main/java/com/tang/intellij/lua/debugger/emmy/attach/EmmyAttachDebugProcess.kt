/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.tang.intellij.lua.debugger.emmy.attach

import com.google.gson.Gson
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.xdebugger.XDebugSession
import com.tang.intellij.lua.debugger.LogConsoleType
import com.tang.intellij.lua.debugger.emmy.EmmyDebugProcessBase
import com.tang.intellij.lua.debugger.emmy.EmmyTargetBootstrap
import com.tang.intellij.lua.debugger.emmy.MessageCMD

/** Emmy attach uses the shared Emmy session and only customizes target preparation. */
class EmmyAttachDebugProcess(session: XDebugSession) : EmmyDebugProcessBase(session) {
    private val configuration = session.runProfile as EmmyAttachDebugConfiguration

    override fun createTargetBootstrap(): EmmyTargetBootstrap =
        EmmyAttachTargetBootstrap(configuration, ::logWithLevel)

    override fun handleBackendMessage(cmd: MessageCMD, json: String): Boolean {
        if (cmd != MessageCMD.AttachedNotify) return false
        val state = runCatching { Gson().fromJson(json, AttachedNotify::class.java).state }.getOrNull()
        val stateText = state?.let { " 0x${it.toString(16)}" }.orEmpty()
        logWithLevel("已附加到 Lua 状态$stateText", LogLevel.NORMAL, null)
        markInitialized()
        return true
    }

    private fun logWithLevel(
        message: String,
        level: LogLevel,
        contentType: ConsoleViewContentType?
    ) {
        if (level.level < configuration.logLevel.level) return
        val outputType = contentType ?: when (level) {
            LogLevel.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
            LogLevel.NORMAL -> ConsoleViewContentType.SYSTEM_OUTPUT
            LogLevel.WARNING -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            LogLevel.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
        }
        println(message, LogConsoleType.NORMAL, outputType)
    }
}

data class AttachedNotify(val state: Long)

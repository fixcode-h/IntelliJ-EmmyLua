/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.tang.intellij.lua.debugger.emmy.attach

import com.google.gson.Gson
import com.intellij.xdebugger.XDebugSession
import com.tang.intellij.lua.debugger.DebugLogLevel
import com.tang.intellij.lua.debugger.emmy.EmmyDebugProcessBase
import com.tang.intellij.lua.debugger.emmy.EmmyTargetBootstrap
import com.tang.intellij.lua.debugger.emmy.MessageCMD

/** Emmy attach uses the shared Emmy session and only customizes target preparation. */
class EmmyAttachDebugProcess(session: XDebugSession) : EmmyDebugProcessBase(session) {
    private val configuration = session.runProfile as EmmyAttachDebugConfiguration

    override val minimumLogLevel: DebugLogLevel
        get() = configuration.logLevel

    override fun createTargetBootstrap(): EmmyTargetBootstrap =
        EmmyAttachTargetBootstrap(configuration, ::log)

    override fun handleBackendMessage(cmd: MessageCMD, json: String): Boolean {
        if (cmd != MessageCMD.AttachedNotify) return false
        val state = runCatching { Gson().fromJson(json, AttachedNotify::class.java).state }.getOrNull()
        val stateText = state?.let { " 0x${it.toString(16)}" }.orEmpty()
        log("已附加到 Lua 状态$stateText", DebugLogLevel.RUNTIME)
        return true
    }
}

data class AttachedNotify(val state: Long)

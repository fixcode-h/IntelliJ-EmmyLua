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
import com.tang.intellij.lua.debugger.emmy.LegacyAttachStatus

/** Emmy attach uses the shared Emmy session and only customizes target preparation. */
class EmmyAttachDebugProcess(session: XDebugSession) : EmmyDebugProcessBase(session) {
    private val configuration = session.runProfile as EmmyAttachDebugConfiguration

    override val minimumLogLevel: DebugLogLevel
        get() = configuration.logLevel

    /** Per-profile opt-in shown in the attach settings; still gated by project trust. */
    override fun configurationAllowsCliAutoGrant(): Boolean = configuration.autoAuthorizeCliClients

    override fun createTargetBootstrap(): EmmyTargetBootstrap =
        EmmyAttachTargetBootstrap(configuration, ::log)

    override fun handleBackendMessage(cmd: MessageCMD, json: String): Boolean {
        if (cmd != MessageCMD.AttachedNotify) return false
        val state = runCatching { Gson().fromJson(json, AttachedNotify::class.java).state }.getOrNull()
        val stateText = state?.let { " 0x${it.toString(16)}" }.orEmpty()
        log("已附加到 Lua 状态$stateText", DebugLogLevel.RUNTIME)
        if (isV2Negotiated()) {
            // v2 carries the authoritative opaque VM id in vm.snapshot. The
            // legacy native state address is diagnostic only; registering a
            // legacy-* VM here would make later v2 pauses resolve to a
            // different identity and be discarded.
            log("v2 已协商，忽略 legacy AttachedNotify 的 VM 注册，仅保留状态地址诊断", DebugLogLevel.DEBUG)
            return true
        }
        state?.let {
            vmRegistry.legacyAttached(it, currentConnectionEpoch())
            if (vmRegistry.legacyAttachStatus() == LegacyAttachStatus.AMBIGUOUS) {
                log("legacy AttachedNotify 已注册，但当前存在多个 VM；为避免误控，不会隐式选择 VM", DebugLogLevel.WARNING)
            }
        }
        return true
    }
}

data class AttachedNotify(val state: Long)

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

package com.tang.intellij.lua.debugger

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project

/**
 * console logger
 * Created by tangzx on 2017/5/1.
 */
interface DebugLogger {
    fun log(text: String, level: DebugLogLevel = DebugLogLevel.RUNTIME)
    fun printHyperlink(text: String, handler: (project: Project) -> Unit)

    fun error(text: String) {
        log(text, DebugLogLevel.ERROR)
    }
}

enum class DebugLogLevel(
    val value: Int,
    val description: String,
    val contentType: ConsoleViewContentType
) {
    DEBUG(0, "调试日志", ConsoleViewContentType.LOG_DEBUG_OUTPUT),
    RUNTIME(1, "运行日志", ConsoleViewContentType.SYSTEM_OUTPUT),
    WARNING(2, "警告日志", ConsoleViewContentType.LOG_WARNING_OUTPUT),
    ERROR(3, "错误日志", ConsoleViewContentType.ERROR_OUTPUT);

    fun isEnabledFor(minimumLevel: DebugLogLevel): Boolean = value >= minimumLevel.value

    override fun toString(): String = "$description (Log$value)"

    companion object {
        fun fromValue(value: Int?): DebugLogLevel =
            entries.firstOrNull { it.value == value } ?: RUNTIME
    }
}

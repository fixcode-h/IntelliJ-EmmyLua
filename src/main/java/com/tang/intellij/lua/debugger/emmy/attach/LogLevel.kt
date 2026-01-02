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

/**
 * 日志输出等级枚举
 */
enum class LogLevel(val level: Int, val description: String) {
    DEBUG(0, "调试日志"),
    NORMAL(1, "普通日志"),
    WARNING(2, "警告日志"),
    ERROR(3, "错误日志");
    
    override fun toString(): String {
        return "$description (级别$level)"
    }
    
    companion object {
        fun fromLevel(level: Int): LogLevel {
            return values().find { it.level == level } ?: NORMAL
        }
    }
}
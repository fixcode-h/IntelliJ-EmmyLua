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

@file:Suppress("MemberVisibilityCanBePrivate", "UNUSED_PARAMETER")

package com.tang.intellij.lua.debugger.emmy

import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicInteger

enum class MessageCMD(val wireId: Int) {
    Unknown(0),

    InitReq(1),
    InitRsp(2),

    ReadyReq(3),
    ReadyRsp(4),

    AddBreakPointReq(5),
    AddBreakPointRsp(6),

    RemoveBreakPointReq(7),
    RemoveBreakPointRsp(8),

    ActionReq(9),
    ActionRsp(10),

    EvalReq(11),
    EvalRsp(12),

    // lua -> ide
    BreakNotify(13),
    AttachedNotify(14),

    StartHookReq(15),
    StartHookRsp(16),

    LogNotify(17);

    companion object {
        private val byWireId = entries.associateBy(MessageCMD::wireId)

        fun fromWireId(wireId: Int): MessageCMD = byWireId[wireId] ?: Unknown
    }
}

interface IMessage {
    val cmd: Int
    fun toJSON(): String
}

open class Message(cmdName: MessageCMD) : IMessage {
    override val cmd = cmdName.wireId

    override fun toJSON(): String {
        return Gson().toJson(this)
    }

    companion object {
        private val seqCount = AtomicInteger()

        fun makeSeq(): Int {
            return seqCount.getAndIncrement()
        }
    }
}

class InitMessage(
    val emmyHelperPath: String,        // emmyHelper 目录路径（插件资源目录）
    val customHelperPath: String = "", // 自定义 helper 目录路径（可选，可断点调试）
    val emmyHelperName: String = "emmyHelper",      // 主 helper 脚本名称
    val emmyHelperExtName: String = "emmyHelper_ue", // 扩展脚本名称（可自定义）
    val ext: Array<String>
) : Message(MessageCMD.InitReq)

enum class DebugAction(val wireId: Int) {
    Break(0),
    Continue(1),
    StepOver(2),
    StepIn(3),
    StepOut(4),
    Stop(5),
}

class DebugActionMessage(actionName: DebugAction) : Message(MessageCMD.ActionReq) {
    val action = actionName.wireId
}

enum class LuaValueType(val wireId: Int) {
    TNIL(0),
    TBOOLEAN(1),
    TLIGHTUSERDATA(2),
    TNUMBER(3),
    TSTRING(4),
    TTABLE(5),
    TFUNCTION(6),
    TUSERDATA(7),
    TTHREAD(8),

    GROUP(9);

    companion object {
        private val byWireId = entries.associateBy(LuaValueType::wireId)

        fun fromWireId(wireId: Int): LuaValueType = byWireId[wireId] ?: TSTRING
    }
}

class VariableValue(val name: String,
                    val nameType: Int,
                    val value: String,
                    val valueType: Int,
                    val valueTypeName: String,
                    val cacheId: Int,
                    val children: List<VariableValue>?) {
    val nameTypeValue: LuaValueType get() {
        return LuaValueType.fromWireId(nameType)
    }

    val nameValue: String get() {
        if (nameTypeValue == LuaValueType.TSTRING)
            return name
        return "[$name]"
    }

    val valueTypeValue: LuaValueType get() {
        return LuaValueType.fromWireId(valueType)
    }

    val fake: Boolean get() {
        return valueTypeValue > LuaValueType.TTHREAD
    }
}

class Stack(
        val file: String,
        val line: Int,
        val functionName: String,
        val level: Int,
        val localVariables: List<VariableValue>,
        val upvalueVariables: List<VariableValue>
)

class BreakNotify(val stacks: List<Stack>)

class EvalReq(val expr: String, val stackLevel: Int, val cacheId: Int, val depth: Int) : Message(MessageCMD.EvalReq) {
    val seq = makeSeq()
}

class EvalRsp(val seq: Int, val success: Boolean, val error: String?, val value: VariableValue?)

class BreakPoint(val file: String, val line: Int, val condition: String? = null, val logMessage: String? = null, hitCondition: String? = null, runToHere: Boolean = false)

class AddBreakPointReq(val breakPoints: List<BreakPoint>) : Message(MessageCMD.AddBreakPointReq)

class RemoveBreakPointReq(val breakPoints: List<BreakPoint>) : Message(MessageCMD.RemoveBreakPointReq)

class LogNotify(val type: Int, val message: String)

class AttachedNotify(val state: Long)

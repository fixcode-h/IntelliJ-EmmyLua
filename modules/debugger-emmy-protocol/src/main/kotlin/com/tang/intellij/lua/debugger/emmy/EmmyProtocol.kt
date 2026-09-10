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

    LogNotify(17),

    /** Versioned envelope command. Legacy wire ids 0..17 must never change. */
    EnvelopeV2(18);

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
    val ext: Array<String>,
    val authToken: String = ""
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
                     val children: List<VariableValue>?,
                     val truncated: Boolean = false) {
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
        val upvalueVariables: List<VariableValue>,
        /** Optional opaque frame identity emitted by v2 agents. */
        val frameId: String = "",
        /** Globals are optional for legacy agents and default to an empty list. */
        val globalVariables: List<VariableValue> = emptyList()
)

class BreakNotify(
    val stacks: List<Stack>,
    val vmId: String? = null,
    val pauseId: Long? = null,
    val threadId: String? = null,
    val pauseScope: String? = null,
    val consistency: String? = null,
    val pauseReason: String? = null,
    /** All independent causes that contributed to this pause. */
    val reasons: List<String> = emptyList()
)

class EvalReq(
    val expr: String,
    val stackLevel: Int,
    val cacheId: Int,
    val depth: Int,
    /** Optional source/frame identity; absent for legacy agents. */
    val sourceIdentity: SourceIdentityWire? = null,
    val vmId: String? = null,
    val pauseId: Long? = null,
    val threadId: String? = null,
    val frameId: String? = null,
    val contextGeneration: Long? = null,
    val sourceEpoch: Long? = null
) : Message(MessageCMD.EvalReq) {
    val seq = makeSeq()
}

class EvalRsp(val seq: Int, val success: Boolean, val error: String?, val value: VariableValue?)

data class BreakPoint(
    val file: String,
    val line: Int,
    val condition: String? = null,
    val logMessage: String? = null,
    val hitCondition: String? = null,
    val runToHere: Boolean = false,
    /** Optional identity fields are ignored by v1 agents that do not understand them. */
    val sourceIdentity: SourceIdentityWire? = null,
    /** Stable owner namespace, for example IDEA or CLI:client-a. */
    val owner: String? = null,
    /** Stable id inside the owner namespace. */
    val breakpointId: String? = null,
    /** Empty means all VMs in the process; otherwise an opaque VM id. */
    val vmId: String? = null,
    /** When true, contributions replace the complete key atomically. */
    val composite: Boolean = false,
    val contributions: List<BreakpointContributionWire> = emptyList()
)

data class BreakpointContributionWire(
    val owner: String,
    val breakpointId: String? = null,
    val condition: String? = null,
    val logMessage: String? = null,
    val hitCondition: String? = null,
    val runToHere: Boolean = false,
    val autoContinue: Boolean = false
)

data class SourceIdentityWire(
    val canonicalPath: String,
    val uri: String,
    val sourceHash: String? = null,
    val loaderEpoch: Long? = null,
    val sourceEpoch: Long? = null,
    val verified: Boolean = false
)

class AddBreakPointReq(
    val breakPoints: List<BreakPoint>,
    val clear: Boolean = false,
    val replaceComposite: Boolean = false
) : Message(MessageCMD.AddBreakPointReq)

class RemoveBreakPointReq(val breakPoints: List<BreakPoint>) : Message(MessageCMD.RemoveBreakPointReq)

class LogNotify(val type: Int, val message: String)

class AttachedNotify(val state: Long)

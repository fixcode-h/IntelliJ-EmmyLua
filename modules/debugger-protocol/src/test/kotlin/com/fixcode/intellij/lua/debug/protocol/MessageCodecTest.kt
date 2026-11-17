package com.fixcode.intellij.lua.debug.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** 线上格式的 golden 用例：字段名和 protocol-v3.md 的示例一致，两端改名会在这里失败。 */
class MessageCodecTest {
    private fun roundTrip(message: ProtocolMessage): ProtocolMessage =
        MessageCodec.decode(FrameDecoder().feed(FrameCodec.encode(MessageCodec.encode(message))).single())

    @Test
    fun `request response event 往返不变`() {
        val request = ProtocolMessage.Request(7, Commands.STACK_TRACE, StackTraceArgs(PauseTarget(1, 3), 0, 20).toJson())
        assertEquals(request, roundTrip(request))
        val failure = ProtocolMessage.Response.failure(8, ErrorCodes.STALE_PAUSE, "这次暂停已经结束")
        assertEquals(failure, roundTrip(failure))
        val event = ProtocolMessage.Event(41, Events.PAUSED, PausedEvent(1, 4, PauseReasons.BREAKPOINT, "t1").toJson())
        assertEquals(event, roundTrip(event))
    }

    @Test
    fun `request 的线上字段名`() {
        val frame = MessageCodec.encode(ProtocolMessage.Request(7, "continue", PauseTarget(1, 3).toJson()))
        assertEquals(
            JsonParser.parseString("""{"id":7,"command":"continue","args":{"vmId":1,"pauseId":3}}"""),
            JsonParser.parseString(String(frame.payload))
        )
    }

    @Test
    fun `解析文档里的 welcome 示例`() {
        val body = JsonParser.parseString(
            """
            {"protocol":3,"revision":0,"sessionId":"c1f0a9e2",
             "runtime":{"name":"emmy_debugger_core","version":"3.0.0","build":"a1b2c3d"},
             "lua":{"version":"5.4","jit":false,"abi":"lua54"},"platform":"windows","arch":"x64",
             "process":{"pid":12345,"name":"MyGame.exe","package":null,"buildId":null},
             "caps":["cbor","valuePath","runToLine"],"maxFrameBytes":16777216,
             "vms":[{"vmId":1,"generation":1,"name":"GameLua","state":"running","luaVersion":"5.4","sourceEpoch":1}]}
            """
        ).asJsonObject
        val welcome = Welcome.fromJson(body)
        assertEquals("c1f0a9e2", welcome.sessionId)
        assertEquals("3.0.0", welcome.runtime.version)
        assertEquals("Lua 5.4", welcome.lua!!.displayName)
        assertEquals(12345L, welcome.process!!.pid)
        assertNull(welcome.process!!.packageName)
        assertEquals(listOf(1L), welcome.vms.map { it.vmId })
        assertFalse(welcome.has(Capabilities.LUA_EVAL))
    }

    @Test
    fun `解析文档里的 paused 和 breakpointHit 示例`() {
        val paused = PausedEvent.fromJson(JsonParser.parseString(
            """{"vmId":1,"pauseId":4,"threadId":"t1","reason":"breakpoint","breakpointIds":["bp-12"],
               "description":"命中断点 bp-12",
               "top":{"frameId":101,"name":"BagPanel:OnItemClick","chunkName":"@Content/Script/UI/BagPanel.lua","line":120},
               "exception":null}"""
        ).asJsonObject)
        assertEquals(PauseTarget(1, 4, "t1"), paused.target())
        assertEquals(120, paused.top!!.line)
        assertEquals(listOf("bp-12"), paused.breakpointIds)

        val hit = BreakpointHitEvent.fromJson(JsonParser.parseString(
            """{"id":"bp-12","vmId":1,"threadId":"t1","hitCount":3,"line":120,"timeMs":1763295300123,
               "captures":[{"expression":"index","value":"7","type":"number"}]}"""
        ).asJsonObject)
        assertEquals(3L, hit.hitCount)
        assertEquals("7", hit.captures.single().value)
    }

    @Test
    fun `断点字段省略默认值`() {
        val json = BreakpointSpec("bp-1", SourceRef("D:/a.lua"), 3).toJson()
        assertEquals(setOf("id", "source", "line"), json.keySet())
        val probe = BreakpointSpec("p", SourceRef("a.lua"), 1, capture = listOf("x"), autoContinue = true, vmId = 2)
        assertEquals(probe, BreakpointSpec.fromJson(probe.toJson()))
    }

    @Test
    fun `缺少必填字段时报出字段名`() {
        val frame = Frame(FrameType.REQUEST, """{"command":"ping"}""".toByteArray())
        try {
            MessageCodec.decode(frame)
            fail()
        } catch (error: ProtocolException) {
            assertEquals("缺少字段 id", error.message)
        }
    }

    @Test
    fun `未知字段被忽略`() {
        val frame = Frame(FrameType.EVENT, """{"seq":1,"event":"resumed","body":{"vmId":1,"by":"continue","future":42}}""".toByteArray())
        val event = MessageCodec.decode(frame) as ProtocolMessage.Event
        assertEquals(ResumedEvent(1, null, "continue"), ResumedEvent.fromJson(event.body))
    }
}

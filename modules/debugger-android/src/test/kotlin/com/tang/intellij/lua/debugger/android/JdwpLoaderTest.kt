package com.tang.intellij.lua.debugger.android

import com.tang.intellij.lua.debugger.android.jdwp.JdwpException
import com.tang.intellij.lua.debugger.android.jdwp.JdwpLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 假 JDWP 服务端：只实现 JdwpLoader 用到的命令，验证命令顺序和 System.load 的参数。
 * ID 一律 8 字节（ART 的实际取值）。
 */
class FakeJdwpVm(private val throwOnLoad: Boolean = false, private val sendVmStartFirst: Boolean = true) {
    val server = ServerSocket(0)
    val commands = CopyOnWriteArrayList<String>()
    @Volatile var loadedPath: String? = null
    @Volatile var resumedThread: Long = 0
    private val strings = HashMap<Long, String>()

    val port: Int get() = server.localPort

    fun start() = thread(isDaemon = true) {
        server.accept().use { s ->
            val input = DataInputStream(s.getInputStream())
            val out = DataOutputStream(s.getOutputStream())
            val hs = ByteArray(14)
            input.readFully(hs)
            out.write(hs)
            if (sendVmStartFirst) event(out, 0, Body().byte(1).int(1).byte(90).int(0).long(0x77))
            while (true) {
                val len = try { input.readInt() } catch (e: Exception) { break }
                val id = input.readInt()
                input.readUnsignedByte()
                val set = input.readUnsignedByte()
                val cmd = input.readUnsignedByte()
                val data = ByteArray(len - 11)
                input.readFully(data)
                val r = DataInputStream(data.inputStream())
                commands += "$set/$cmd"
                when ("$set/$cmd") {
                    "1/7" -> reply(out, id, Body().int(8).int(8).int(8).int(8).int(8))
                    "1/2" -> {
                        val sig = readString(r)
                        val cls = mapOf("Landroid/os/Handler;" to 100L, "Ljava/lang/System;" to 200L)[sig]
                        if (cls == null) reply(out, id, Body().int(0)) else reply(out, id, Body().int(1).byte(1).long(cls).int(1))
                    }
                    "2/5" -> {
                        val cls = r.readLong()
                        val methods = if (cls == 100L) listOf(Triple(101L, "dispatchMessage", "(Landroid/os/Message;)V"), Triple(102L, "post", "(Ljava/lang/Runnable;)Z"))
                        else listOf(Triple(201L, "loadLibrary", "(Ljava/lang/String;)V"), Triple(202L, "load", "(Ljava/lang/String;)V"))
                        val b = Body().int(methods.size)
                        methods.forEach { (mid, n, sig) -> b.long(mid).string(n).string(sig).int(9) }
                        reply(out, id, b)
                    }
                    "15/1" -> {
                        reply(out, id, Body().int(42))
                        // 命中断点：线程 0x55 在 Handler.dispatchMessage 入口
                        event(out, 2, Body().byte(1).int(1).byte(2).int(42).long(0x55).byte(1).long(100).long(101).long(0))
                    }
                    "15/2" -> reply(out, id, Body())
                    "1/11" -> {
                        val s = readString(r)
                        strings[0x900L] = s
                        reply(out, id, Body().long(0x900))
                    }
                    "3/3" -> {
                        val cls = r.readLong(); val threadId = r.readLong(); val method = r.readLong()
                        r.readInt(); r.readByte(); val arg = r.readLong()
                        if (cls == 200L && method == 202L && threadId == 0x55L) loadedPath = strings[arg]
                        reply(out, id, Body().byte('V'.code).byte('L'.code).long(if (throwOnLoad) 0x999 else 0))
                    }
                    "11/3" -> {
                        resumedThread = r.readLong()
                        reply(out, id, Body())
                    }
                    "1/6" -> { reply(out, id, Body()); break }
                    else -> reply(out, id, Body(), error = 99)
                }
            }
        }
    }

    private fun readString(r: DataInputStream): String {
        val n = r.readInt()
        val b = ByteArray(n)
        r.readFully(b)
        return String(b)
    }

    private fun reply(out: DataOutputStream, id: Int, body: Body, error: Int = 0) {
        val b = body.bytes()
        out.writeInt(11 + b.size); out.writeInt(id); out.writeByte(0x80); out.writeShort(error); out.write(b); out.flush()
    }

    private fun event(out: DataOutputStream, id: Int, body: Body) {
        val b = body.bytes()
        out.writeInt(11 + b.size); out.writeInt(1000 + id); out.writeByte(0); out.writeByte(64); out.writeByte(100); out.write(b); out.flush()
    }

    class Body {
        private val buf = ByteArrayOutputStream()
        private val o = DataOutputStream(buf)
        fun byte(v: Int) = apply { o.writeByte(v) }
        fun int(v: Int) = apply { o.writeInt(v) }
        fun long(v: Long) = apply { o.writeLong(v) }
        fun string(s: String) = apply { val b = s.toByteArray(); o.writeInt(b.size); o.write(b) }
        fun bytes(): ByteArray = buf.toByteArray()
    }
}

class JdwpLoaderTest {
    @Test
    fun loadsLibraryOnBreakpointThread() {
        val vm = FakeJdwpVm()
        val t = vm.start()
        JdwpLoader("127.0.0.1", vm.port).loadLibrary("/data/user/0/com.a.b/code_cache/emmylua/libemmylua_agent.so", 5_000)
        t.join(2_000)
        assertEquals("/data/user/0/com.a.b/code_cache/emmylua/libemmylua_agent.so", vm.loadedPath)
        assertEquals(0x55L, vm.resumedThread)
        // 先清断点再调用，最后 Dispose
        val c = vm.commands
        assertTrue(c.indexOf("15/2") < c.indexOf("3/3"))
        assertEquals("1/6", c.last())
    }

    @Test
    fun reportsExceptionFromLoad() {
        val vm = FakeJdwpVm(throwOnLoad = true, sendVmStartFirst = false)
        vm.start()
        try {
            JdwpLoader("127.0.0.1", vm.port).loadLibrary("/x/lib.so", 5_000)
            fail()
        } catch (e: JdwpException) {
            assertTrue(e.message!!.contains("System.load"))
        }
        // 出错也要恢复线程，不能把游戏主线程停住
        Thread.sleep(100)
        assertEquals(0x55L, vm.resumedThread)
    }
}

package com.tang.intellij.lua.debug.engine.transport

import com.tang.intellij.lua.debug.protocol.FrameCodec
import com.tang.intellij.lua.debug.protocol.FrameDecoder
import com.tang.intellij.lua.debug.protocol.MessageCodec
import com.tang.intellij.lua.debug.protocol.ProtocolMessage
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一条已建立的 v3 消息连接。只负责收发消息，不懂会话语义。
 * 监听器在连接自己的读线程上回调，引擎会马上把它投递到会话线程。
 */
interface MessageConnection : AutoCloseable {
    val description: String

    /**
     * 建连时才知道的 token（例如真机附加：agent 注入后才生成）。非空时覆盖 [com.tang.intellij.lua.debug.engine.SessionConfig.token]。
     */
    val token: String? get() = null

    fun start(listener: Listener)

    /** 线程安全；连接已关闭时抛 [IOException]。 */
    fun send(message: ProtocolMessage)

    override fun close()

    interface Listener {
        fun onMessage(message: ProtocolMessage)

        /** 连接结束时恰好调用一次；[cause] 为空表示正常关闭。 */
        fun onClosed(cause: Throwable?)
    }
}

/** 建连策略：附加（连 PID 派生端口）、真机（先建 adb 转发）、手动地址都实现它。会阻塞，引擎在 IO 线程调用。 */
fun interface Connector {
    @Throws(IOException::class)
    fun connect(): MessageConnection

    /**
     * 调试目标（游戏进程）是否还活着：true / false 是确定的结论，null 表示这种目标查不了（手动地址、真机）。
     * 心跳没回应时引擎靠它区分「进程没了」和「进程还在、只是不响应」（被 VS 等调试器暂停、卡在长帧里）。
     * 会阻塞，引擎在 IO 线程调用。
     */
    fun isTargetAlive(): Boolean? = null
}

/**
 * 调试目标已经不在了（进程退出、被关闭），重连没有意义：引擎收到后直接结束会话，不再重试。
 * [message] 原样显示给用户。
 */
class TargetGoneException(message: String) : IOException(message)

class TcpConnector(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMillis: Int = 3000,
    private val token: String? = null
) : Connector {
    override fun connect(): MessageConnection {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        } catch (error: IOException) {
            socket.close()
            throw IOException("连不上调试 runtime（$host:$port）：${error.message}", error)
        }
        return StreamMessageConnection(socket.getInputStream(), socket.getOutputStream(), socket, "$host:$port", token)
    }

    override fun toString(): String = "tcp://$host:$port"
}

/** 基于字节流的连接：一个读线程按帧解码，写入加锁。TCP、adb 转发后的本机端口都用它。 */
class StreamMessageConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val resource: AutoCloseable,
    override val description: String,
    override val token: String? = null
) : MessageConnection {
    private val closed = AtomicBoolean()
    private val writeLock = Any()
    @Volatile
    private var listener: MessageConnection.Listener? = null
    private val closeNotified = AtomicBoolean()

    override fun start(listener: MessageConnection.Listener) {
        check(this.listener == null) { "连接已经启动" }
        this.listener = listener
        Thread({ readLoop(listener) }, "EmmyLua-Debug-Reader-$description").apply {
            isDaemon = true
            start()
        }
    }

    override fun send(message: ProtocolMessage) {
        if (closed.get()) throw IOException("调试连接已关闭")
        val bytes = FrameCodec.encode(MessageCodec.encode(message))
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 关掉底层资源会让读线程的 read 抛出或返回 -1，由读线程统一通知 onClosed
        try {
            resource.close()
        } catch (_: Exception) {
        }
    }

    private fun readLoop(listener: MessageConnection.Listener) {
        val decoder = FrameDecoder()
        val buffer = ByteArray(64 * 1024)
        var failure: Throwable? = null
        try {
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                for (frame in decoder.feed(buffer, 0, read)) {
                    listener.onMessage(MessageCodec.decode(frame))
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) failure = error
        } finally {
            closed.set(true)
            try {
                resource.close()
            } catch (_: Exception) {
            }
            if (closeNotified.compareAndSet(false, true)) listener.onClosed(failure)
        }
    }
}

package com.fixcode.intellij.lua.debug.engine.fake

import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.DebugSession

/** 记录会话事件，测试按顺序等待。[await] 从游标往后找，找到后游标移过它。 */
class EventRecorder(session: DebugSession) : AutoCloseable {
    private val lock = Object()
    private val events = ArrayList<DebugEvent>()
    private var cursor = 0
    private val subscription = session.events.subscribe { event ->
        synchronized(lock) {
            events += event
            lock.notifyAll()
        }
    }

    fun all(): List<DebugEvent> = synchronized(lock) { events.toList() }

    inline fun <reified T : DebugEvent> await(timeoutMillis: Long = 5_000, noinline predicate: (T) -> Boolean = { true }): T =
        awaitMatching(T::class.java, timeoutMillis, predicate)

    fun <T : DebugEvent> awaitMatching(type: Class<T>, timeoutMillis: Long, predicate: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        synchronized(lock) {
            while (true) {
                for (index in cursor until events.size) {
                    val event = events[index]
                    if (type.isInstance(event) && predicate(type.cast(event))) {
                        cursor = index + 1
                        return type.cast(event)
                    }
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    throw AssertionError("等了 $timeoutMillis ms 没等到 ${type.simpleName}；游标后的事件：${events.drop(cursor)}")
                }
                lock.wait(remaining)
            }
        }
    }

    /** 游标之后（不含）没有满足条件的事件，等 [quietMillis] 确认。 */
    inline fun <reified T : DebugEvent> assertNone(quietMillis: Long = 200, noinline predicate: (T) -> Boolean = { true }) {
        Thread.sleep(quietMillis)
        val unexpected = remaining().filterIsInstance<T>().filter(predicate)
        if (unexpected.isNotEmpty()) throw AssertionError("不应出现的事件：$unexpected")
    }

    fun remaining(): List<DebugEvent> = synchronized(lock) { events.drop(cursor) }

    override fun close() = subscription.close()
}

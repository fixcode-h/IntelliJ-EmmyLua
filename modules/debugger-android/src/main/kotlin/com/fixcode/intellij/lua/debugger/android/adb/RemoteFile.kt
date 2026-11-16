package com.fixcode.intellij.lua.debugger.android.adb

import com.fixcode.intellij.lua.debugger.android.elf.ByteSource
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 经 adb 按偏移读设备上的文件（以应用身份 run-as，APK 和解压出的 so 都能读）。
 * 用 `dd bs=4096 skip=` 取对齐的块，`exec-out` 原样带回字节；小读取按 64 KB 块缓存，
 * 解析 ELF 头、节头、.dynsym 只要几次 adb 往返，大块读取（特征码扫代码段）直接取。
 *
 * [base] 是文件里 so 的起点（APK 内直接映射时是 so 在 APK 里的偏移，必须 4 KB 对齐）。
 */
class RemoteFileSource(
    private val adb: AdbClient,
    private val serial: String,
    private val packageName: String,
    private val path: String,
    private val base: Long = 0,
    override val size: Long = Long.MAX_VALUE,
) : ByteSource {
    init {
        AndroidDevices.requireValidPackage(packageName)
        AndroidDevices.requireSafePath(path)
        require(base % PAGE == 0L) { "so 在文件里的偏移没有按 4 KB 对齐：$base" }
    }

    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
    }

    /** adb 调用次数，给测试和日志看。 */
    var fetches = 0
        private set

    override fun read(dst: ByteBuffer, position: Long): Int {
        if (position >= size || !dst.hasRemaining()) return -1
        val want = minOf(dst.remaining().toLong(), size - position).toInt()
        if (want >= LARGE) {
            val bytes = fetch(position, want)
            if (bytes.isEmpty()) return -1
            dst.put(bytes, 0, bytes.size)
            return bytes.size
        }
        var copied = 0
        while (copied < want) {
            val pos = position + copied
            val blockStart = pos / BLOCK * BLOCK
            val block = blocks[blockStart] ?: fetch(blockStart, BLOCK).also { blocks[blockStart] = it }
            val inBlock = (pos - blockStart).toInt()
            if (inBlock >= block.size) break
            val n = minOf(want - copied, block.size - inBlock)
            dst.put(block, inBlock, n)
            copied += n
            if (block.size < BLOCK) break
        }
        return if (copied == 0) -1 else copied
    }

    /** 读 [position] 起的 [length] 字节；到文件尾时返回更短的数组。 */
    fun fetch(position: Long, length: Int): ByteArray {
        val abs = base + position
        val first = abs / PAGE
        val last = (abs + length + PAGE - 1) / PAGE
        fetches++
        val raw = adb.execOut(serial, "run-as $packageName dd if=$path bs=$PAGE skip=$first count=${last - first} 2>/dev/null",
            timeoutMs = 60_000 + length / 1024L * 20)
        val skip = (abs - first * PAGE).toInt()
        if (raw.size <= skip) return ByteArray(0)
        return raw.copyOfRange(skip, minOf(raw.size, skip + length))
    }

    companion object {
        const val PAGE = 4096L
        private const val BLOCK = 64 * 1024
        private const val MAX_BLOCKS = 64
        private const val LARGE = 1024 * 1024
    }
}

/**
 * 在 APK（zip）里找不压缩存放的 so：`extractNativeLibs=false`（AGP 默认）时 so 原样放在 APK 里、
 * 按页对齐，系统直接从 APK 映射。只读 zip 尾部的中央目录和一个本地文件头。
 */
object ApkZip {
    /** 返回 [entryName] 数据在 zip 里的偏移；不存在、压缩过或 zip 坏了返回 null。 */
    fun findStoredEntry(source: ByteSource, size: Long, entryName: String): Long? {
        if (size < 22) return null
        val tailLen = minOf(size, 22L + 65535).toInt()
        val tail = read(source, size - tailLen, tailLen) ?: return null
        var eocd = -1
        for (i in tail.limit() - 22 downTo 0) {
            if (tail.getInt(i) == 0x06054b50) {
                eocd = i
                break
            }
        }
        if (eocd < 0) return null
        val cdSize = tail.getInt(eocd + 12).toLong() and 0xffffffffL
        val cdOffset = tail.getInt(eocd + 16).toLong() and 0xffffffffL
        if (cdSize <= 0 || cdOffset + cdSize > size || cdSize > 64L * 1024 * 1024) return null
        val cd = read(source, cdOffset, cdSize.toInt()) ?: return null
        val wanted = entryName.toByteArray(Charsets.UTF_8)
        var p = 0
        while (p + 46 <= cd.limit()) {
            if (cd.getInt(p) != 0x02014b50) return null
            val method = cd.getShort(p + 10).toInt() and 0xffff
            val nameLen = cd.getShort(p + 28).toInt() and 0xffff
            val extraLen = cd.getShort(p + 30).toInt() and 0xffff
            val commentLen = cd.getShort(p + 32).toInt() and 0xffff
            val localOffset = cd.getInt(p + 42).toLong() and 0xffffffffL
            if (p + 46 + nameLen > cd.limit()) return null
            if (nameLen == wanted.size && (0 until nameLen).all { cd.get(p + 46 + it) == wanted[it] }) {
                if (method != 0) return null
                val local = read(source, localOffset, 30) ?: return null
                if (local.getInt(0) != 0x04034b50) return null
                val ln = local.getShort(26).toInt() and 0xffff
                val le = local.getShort(28).toInt() and 0xffff
                return localOffset + 30 + ln + le
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        return null
    }

    private fun read(source: ByteSource, offset: Long, len: Int): ByteBuffer? {
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = source.read(buf, pos)
            if (n <= 0) return null
            pos += n
        }
        buf.flip()
        return buf
    }
}

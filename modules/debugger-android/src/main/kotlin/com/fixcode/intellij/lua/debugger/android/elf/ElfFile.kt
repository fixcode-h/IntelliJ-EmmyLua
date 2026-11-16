package com.fixcode.intellij.lua.debugger.android.elf

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * 只读的 ELF 解析器，只实现真机附加需要的部分：文件头、程序头、节头、符号表、GNU Build ID。
 *
 * 未裁剪的 libUnreal.so 常见 1~3 GB，超过 MappedByteBuffer 的 2 GB 上限，
 * 所以这里全部按偏移读取（FileChannel.read），不整体映射，也不把整张字符串表读进内存。
 */
class ElfFile private constructor(private val source: ByteSource, val path: String) : Closeable {

    val is64: Boolean
    val machine: Int
    val type: Int
    val programHeaders: List<ProgramHeader>
    val sections: List<SectionHeader>

    init {
        val ident = read(0, 16)
        if (ident.getInt(0) != ELF_MAGIC) throw ElfFormatException("不是 ELF 文件：$path")
        is64 = when (ident.get(4).toInt()) {
            1 -> false
            2 -> true
            else -> throw ElfFormatException("未知的 ELF 位宽：$path")
        }
        if (ident.get(5).toInt() != 1) {
            // Android 只有小端 ABI，大端文件一定是拿错了
            throw ElfFormatException("只支持小端 ELF：$path")
        }
        val header = read(0, if (is64) 64 else 52)
        type = header.getShort(16).toInt() and 0xffff
        machine = header.getShort(18).toInt() and 0xffff
        val phoff: Long
        val shoff: Long
        val phentsize: Int
        val phnum: Int
        val shentsize: Int
        val shnum: Int
        val shstrndx: Int
        if (is64) {
            phoff = header.getLong(32)
            shoff = header.getLong(40)
            phentsize = header.getShort(54).toInt() and 0xffff
            phnum = header.getShort(56).toInt() and 0xffff
            shentsize = header.getShort(58).toInt() and 0xffff
            shnum = header.getShort(60).toInt() and 0xffff
            shstrndx = header.getShort(62).toInt() and 0xffff
        } else {
            phoff = header.getInt(28).toLong() and 0xffffffffL
            shoff = header.getInt(32).toLong() and 0xffffffffL
            phentsize = header.getShort(42).toInt() and 0xffff
            phnum = header.getShort(44).toInt() and 0xffff
            shentsize = header.getShort(46).toInt() and 0xffff
            shnum = header.getShort(48).toInt() and 0xffff
            shstrndx = header.getShort(50).toInt() and 0xffff
        }
        programHeaders = if (phoff == 0L || phnum == 0) emptyList() else {
            val buf = read(phoff, phentsize * phnum)
            (0 until phnum).map { parseProgramHeader(buf, it * phentsize) }
        }
        val rawSections = if (shoff == 0L || shnum == 0) emptyList() else {
            val buf = read(shoff, shentsize * shnum)
            (0 until shnum).map { parseSectionHeader(buf, it * shentsize) }
        }
        sections = if (shstrndx in rawSections.indices) {
            val names = rawSections[shstrndx]
            val table = read(names.offset, names.size.toInt())
            rawSections.map { it.copy(name = cString(table, it.nameIndex)) }
        } else rawSections
    }

    val abi: String?
        get() = when (machine) {
            EM_AARCH64 -> "arm64-v8a"
            EM_ARM -> "armeabi-v7a"
            EM_X86_64 -> "x86_64"
            EM_386 -> "x86"
            else -> null
        }

    fun section(name: String): SectionHeader? = sections.firstOrNull { it.name == name }

    /**
     * GNU Build ID（十六进制小写）。先找 .note.gnu.build-id 节，没有节头时退回 PT_NOTE 段。
     * 设备上的 so 被 strip 过也保留这个 note，所以两边可以拿它比对是否同一次构建。
     */
    fun buildId(): String? {
        section(".note.gnu.build-id")?.let { s ->
            findGnuBuildId(read(s.offset, s.size.toInt()))?.let { return it }
        }
        for (s in sections.filter { it.type == SHT_NOTE }) {
            findGnuBuildId(read(s.offset, s.size.toInt()))?.let { return it }
        }
        for (p in programHeaders.filter { it.type == PT_NOTE }) {
            findGnuBuildId(read(p.offset, p.fileSize.toInt()))?.let { return it }
        }
        return null
    }

    /**
     * 遍历符号表（.symtab 优先，再 .dynsym）。[nameFilter] 先按字节判断，
     * 通过后才构造字符串，避免给几十万个符号都分配 String。
     */
    fun forEachSymbol(
        nameFilter: (ByteArray, Int, Int) -> Boolean,
        tables: List<String> = listOf(".symtab", ".dynsym"),
        consumer: (ElfSymbol) -> Unit,
    ) {
        for (tableName in tables) {
            val table = section(tableName) ?: continue
            if (table.link !in sections.indices) continue
            val strtab = sections[table.link]
            val strings = StringTableReader(this, strtab.offset, strtab.size)
            val entSize = if (table.entSize > 0) table.entSize.toInt() else if (is64) 24 else 16
            val count = table.size / entSize
            val batch = 4096
            var index = 0L
            while (index < count) {
                val n = minOf(batch.toLong(), count - index).toInt()
                val buf = read(table.offset + index * entSize, n * entSize)
                for (i in 0 until n) {
                    val sym = parseSymbol(buf, i * entSize, tableName)
                    if (sym.nameIndex == 0) continue
                    val name = strings.nameIfMatches(sym.nameIndex, nameFilter) ?: continue
                    consumer(sym.copy(name = name))
                }
                index += n
            }
        }
    }

    internal fun read(offset: Long, length: Int): ByteBuffer {
        if (length < 0) throw ElfFormatException("长度异常：$length")
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = source.read(buf, pos)
            if (n < 0) throw ElfFormatException("文件被截断：$path（偏移 $pos）")
            pos += n
        }
        buf.flip()
        return buf
    }

    /** 文件大小；远程读取（设备上的 so）时可能未知，返回 [Long.MAX_VALUE]。 */
    internal val fileSize: Long get() = source.size

    override fun close() = source.close()

    /** 读 [length] 字节到新数组。 */
    fun readBytes(offset: Long, length: Int): ByteArray {
        val b = read(offset, length)
        return ByteArray(length).also { b.get(it) }
    }

    /** 可执行的 PT_LOAD 段：特征码只在这些段里搜。 */
    fun executableSegments(): List<ProgramHeader> =
        programHeaders.filter { it.type == PT_LOAD && (it.flags and PF_X) != 0 && it.fileSize > 0 }

    /** 虚拟地址 → 文件偏移（按 PT_LOAD 换算）；不在任何段的文件内容里返回 null。 */
    fun vaddrToOffset(vaddr: Long): Long? {
        for (p in programHeaders) {
            if (p.type != PT_LOAD) continue
            if (vaddr >= p.vaddr && vaddr < p.vaddr + p.fileSize) return p.offset + (vaddr - p.vaddr)
        }
        return null
    }

    private fun parseProgramHeader(b: ByteBuffer, o: Int): ProgramHeader = if (is64) ProgramHeader(
        type = b.getInt(o),
        flags = b.getInt(o + 4),
        offset = b.getLong(o + 8),
        vaddr = b.getLong(o + 16),
        fileSize = b.getLong(o + 32),
        memSize = b.getLong(o + 40),
        align = b.getLong(o + 48),
    ) else ProgramHeader(
        type = b.getInt(o),
        offset = b.u32(o + 4),
        vaddr = b.u32(o + 8),
        fileSize = b.u32(o + 16),
        memSize = b.u32(o + 20),
        flags = b.getInt(o + 24),
        align = b.u32(o + 28),
    )

    private fun parseSectionHeader(b: ByteBuffer, o: Int): SectionHeader = if (is64) SectionHeader(
        nameIndex = b.getInt(o),
        type = b.getInt(o + 4),
        flags = b.getLong(o + 8),
        addr = b.getLong(o + 16),
        offset = b.getLong(o + 24),
        size = b.getLong(o + 32),
        link = b.getInt(o + 40),
        info = b.getInt(o + 44),
        entSize = b.getLong(o + 56),
    ) else SectionHeader(
        nameIndex = b.getInt(o),
        type = b.getInt(o + 4),
        flags = b.u32(o + 8),
        addr = b.u32(o + 12),
        offset = b.u32(o + 16),
        size = b.u32(o + 20),
        link = b.getInt(o + 24),
        info = b.getInt(o + 28),
        entSize = b.u32(o + 36),
    )

    private fun parseSymbol(b: ByteBuffer, o: Int, table: String): ElfSymbol = if (is64) ElfSymbol(
        nameIndex = b.getInt(o),
        info = b.get(o + 4).toInt() and 0xff,
        sectionIndex = b.getShort(o + 6).toInt() and 0xffff,
        value = b.getLong(o + 8),
        size = b.getLong(o + 16),
        table = table,
    ) else ElfSymbol(
        nameIndex = b.getInt(o),
        value = b.u32(o + 4),
        size = b.u32(o + 8),
        info = b.get(o + 12).toInt() and 0xff,
        sectionIndex = b.getShort(o + 14).toInt() and 0xffff,
        table = table,
    )

    companion object {
        const val ELF_MAGIC = 0x464c457f // "\u007fELF" 小端读
        const val EM_386 = 3
        const val EM_ARM = 40
        const val EM_X86_64 = 62
        const val EM_AARCH64 = 183
        const val PT_LOAD = 1
        const val PF_X = 1
        const val PT_NOTE = 4
        const val SHT_NOTE = 7
        const val STT_FUNC = 2
        const val SHN_UNDEF = 0
        private const val NT_GNU_BUILD_ID = 3

        fun open(file: File): ElfFile = open(FileByteSource(FileChannel.open(file.toPath(), StandardOpenOption.READ)), file.path)

        /** 内存里的 ELF，例如 .gnu_debugdata 解压出来的 MiniDebugInfo。 */
        fun fromBytes(bytes: ByteArray, name: String): ElfFile = open(ArrayByteSource(bytes), name)

        /** 任意来源（例如经 adb 按需读取的设备 so）。打开失败时会关闭 [source]。 */
        fun open(source: ByteSource, name: String): ElfFile {
            try {
                return ElfFile(source, name)
            } catch (e: Throwable) {
                source.close()
                throw e
            }
        }

        fun findGnuBuildId(notes: ByteBuffer): String? {
            val b = notes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0
            while (pos + 12 <= b.limit()) {
                val nameSize = b.getInt(pos)
                val descSize = b.getInt(pos + 4)
                val type = b.getInt(pos + 8)
                if (nameSize < 0 || descSize < 0) return null
                val nameStart = pos + 12
                val descStart = nameStart + align4(nameSize)
                val next = descStart + align4(descSize)
                if (next > b.limit() || descStart > b.limit()) return null
                if (type == NT_GNU_BUILD_ID && nameSize == 4 &&
                    b.get(nameStart) == 'G'.code.toByte() && b.get(nameStart + 1) == 'N'.code.toByte() &&
                    b.get(nameStart + 2) == 'U'.code.toByte()
                ) {
                    val sb = StringBuilder(descSize * 2)
                    for (i in 0 until descSize) sb.append("%02x".format(b.get(descStart + i).toInt() and 0xff))
                    return sb.toString()
                }
                pos = next
            }
            return null
        }

        private fun align4(v: Int) = (v + 3) and 3.inv()

        private fun cString(b: ByteBuffer, start: Int): String {
            if (start < 0 || start >= b.limit()) return ""
            var end = start
            while (end < b.limit() && b.get(end).toInt() != 0) end++
            val bytes = ByteArray(end - start)
            b.duplicate().apply { position(start) }.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}

/** ELF 的字节来源：本地文件、内存、或经 adb 按需读取的设备文件。只需要按偏移读。 */
interface ByteSource : Closeable {
    /** 从 [position] 读到 [dst]，返回读到的字节数；文件结束返回 -1。 */
    fun read(dst: ByteBuffer, position: Long): Int
    val size: Long
    override fun close() {}
}

class FileByteSource(private val channel: FileChannel) : ByteSource {
    override fun read(dst: ByteBuffer, position: Long): Int = channel.read(dst, position)
    override val size: Long get() = channel.size()
    override fun close() = channel.close()
}

class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override fun read(dst: ByteBuffer, position: Long): Int {
        if (position >= bytes.size) return -1
        val n = minOf(dst.remaining().toLong(), bytes.size - position).toInt()
        dst.put(bytes, position.toInt(), n)
        return n
    }
    override val size: Long get() = bytes.size.toLong()
}

private fun ByteBuffer.u32(o: Int): Long = getInt(o).toLong() and 0xffffffffL

data class ProgramHeader(
    val type: Int,
    val flags: Int,
    val offset: Long,
    val vaddr: Long,
    val fileSize: Long,
    val memSize: Long,
    val align: Long,
)

data class SectionHeader(
    val nameIndex: Int,
    val type: Int,
    val flags: Long,
    val addr: Long,
    val offset: Long,
    val size: Long,
    val link: Int,
    val info: Int,
    val entSize: Long,
    val name: String = "",
)

data class ElfSymbol(
    val nameIndex: Int,
    val info: Int,
    val sectionIndex: Int,
    val value: Long,
    val size: Long,
    val table: String,
    val name: String = "",
) {
    val isFunction: Boolean get() = (info and 0xf) == ElfFile.STT_FUNC
    val isDefined: Boolean get() = sectionIndex != ElfFile.SHN_UNDEF
}

class ElfFormatException(message: String) : IOException(message)

/**
 * 按需读取字符串表：一次读一个 64 KB 窗口，同一窗口内的符号名不再重复读盘。
 * 符号表基本按字符串表顺序排列，窗口命中率很高。
 */
private class StringTableReader(private val elf: ElfFile, private val base: Long, private val size: Long) {
    private var windowStart = -1L
    private var window = ByteArray(0)
    private var windowLen = 0

    fun nameIfMatches(index: Int, filter: (ByteArray, Int, Int) -> Boolean): String? {
        val off = index.toLong() and 0xffffffffL
        if (off >= size) return null
        var bytes = window
        var start = (off - windowStart).toInt()
        if (windowStart < 0 || off < windowStart || off >= windowStart + windowLen || !hasTerminator(start)) {
            load(off)
            bytes = window
            start = 0
        }
        var end = start
        while (end < windowLen && bytes[end].toInt() != 0) end++
        if (end >= windowLen) {
            // 名字比窗口还长（极长的 C++ 模板名），这种名字不可能是我们要的
            return null
        }
        if (!filter(bytes, start, end - start)) return null
        return String(bytes, start, end - start, Charsets.UTF_8)
    }

    private fun hasTerminator(start: Int): Boolean {
        for (i in start until windowLen) if (window[i].toInt() == 0) return true
        return false
    }

    private fun load(off: Long) {
        val len = minOf(WINDOW.toLong(), size - off).toInt()
        val buf = elf.read(base + off, len)
        if (window.size < len) window = ByteArray(WINDOW)
        buf.get(window, 0, len)
        windowStart = off
        windowLen = len
    }

    companion object {
        const val WINDOW = 64 * 1024
    }
}

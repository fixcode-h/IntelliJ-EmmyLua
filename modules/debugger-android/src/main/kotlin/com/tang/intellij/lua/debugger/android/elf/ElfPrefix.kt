package com.tang.intellij.lua.debugger.android.elf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 只拿到 so 开头几 KB 时取 Build ID：从设备上读整个 libUnreal.so（几百 MB）太慢，
 * 而 PT_NOTE 段通常紧跟程序头，在第一页之内。
 */
object ElfPrefix {
    fun buildId(prefix: ByteArray): String? {
        if (prefix.size < 52) return null
        val b = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != ElfFile.ELF_MAGIC || b.get(5).toInt() != 1) return null
        val is64 = b.get(4).toInt() == 2
        if (is64 && prefix.size < 64) return null
        val phoff = if (is64) b.getLong(32) else b.getInt(28).toLong() and 0xffffffffL
        val phentsize = (if (is64) b.getShort(54) else b.getShort(42)).toInt() and 0xffff
        val phnum = (if (is64) b.getShort(56) else b.getShort(44)).toInt() and 0xffff
        for (i in 0 until phnum) {
            val o = phoff + i.toLong() * phentsize
            if (o + phentsize > prefix.size) return null
            val off = o.toInt()
            val type = b.getInt(off)
            if (type != ElfFile.PT_NOTE) continue
            val noteOffset = if (is64) b.getLong(off + 8) else b.getInt(off + 4).toLong() and 0xffffffffL
            val noteSize = if (is64) b.getLong(off + 32) else b.getInt(off + 16).toLong() and 0xffffffffL
            if (noteOffset < 0 || noteOffset + noteSize > prefix.size) continue
            val slice = ByteBuffer.wrap(prefix, noteOffset.toInt(), noteSize.toInt()).slice().order(ByteOrder.LITTLE_ENDIAN)
            ElfFile.findGnuBuildId(slice)?.let { return it }
        }
        return null
    }
}

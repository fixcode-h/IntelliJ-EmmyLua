package com.fixcode.intellij.lua.debugger.android.elf

import java.util.BitSet

/** 指令集：决定怎么切指令、哪些字段和地址有关。 */
enum class CodeArch(
    /** 函数起点和搜索步长的对齐。 */
    val alignment: Int,
) {
    ARM64(4),
    /** armv7 的 Thumb / Thumb-2（Android armeabi-v7a 默认），符号值最低位是 1。 */
    THUMB(2),
    /** armv7 的 ARM（A32）模式。 */
    ARM(4),
}

/**
 * 一段带通配的机器码：[mask] 里为 0 的位不参与比较。[bytes] 已经和 [mask] 按位与过。
 */
class MaskedCode(val bytes: ByteArray, val mask: ByteArray) {
    val length: Int get() = bytes.size

    /** [data] 从 [offset] 起的前 [len] 字节是否匹配本特征的前 [len] 字节。 */
    fun matches(data: ByteArray, offset: Int, len: Int): Boolean {
        for (i in 0 until len) {
            if (((data[offset + i].toInt() xor bytes[i].toInt()) and mask[i].toInt()) != 0) return false
        }
        return true
    }

    /** 从 [offset] 起的 4 字节是否全部是固定位（可以当作搜索锚点）。 */
    fun isFixedWord(offset: Int): Boolean =
        offset + 4 <= length && (0 until 4).all { mask[offset + it] == 0xff.toByte() }

    fun wordAt(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)

    /** 固定位的比例，给日志和测试看。 */
    fun fixedRatio(len: Int = length): Double {
        var bits = 0
        for (i in 0 until len) bits += Integer.bitCount(mask[i].toInt() and 0xff)
        return if (len == 0) 0.0 else bits / (len * 8.0)
    }
}

/**
 * 把参考 so 里一个函数的机器码变成特征：和地址有关的立即数（跳转、PC 相对寻址、文字池）通配掉，
 * 这样同一份源码在另一次构建里（布局变了、调用目标挪了）仍然能匹配上。
 *
 * - arm64：ADRP / ADR、B / BL、B.cond、CBZ / CBNZ、TBZ / TBNZ、LDR（literal）的偏移字段；
 *   另外 ADRP 之后以它的目标寄存器为基址的 ADD / LDR / STR 立即数（:lo12: 低 12 位）也通配。
 * - Thumb / Thumb-2：B、B.cond、CBZ / CBNZ、BL / BLX、B.W、LDR（literal）、ADR、MOVW / MOVT；
 *   LDR literal 读的文字池如果落在函数前 N 字节里，池里的 4 字节（通常是 GOT 偏移）也通配，并且不当指令解码。
 * - ARM（A32）：B / BL / BLX、LDR（literal）和它的文字池、MOVW / MOVT。
 *
 * 只往宽里通配（多通配不会让真正的函数匹配不上，只会让特征变弱，靠「只接受唯一匹配」兜住）。
 */
object ArmCodeMasker {

    /** [code] 是函数从入口开始的字节；[address] 是入口的虚拟地址（Thumb 已去掉最低位），用来算文字池位置。 */
    fun mask(code: ByteArray, arch: CodeArch, address: Long = 0): MaskedCode {
        val mask = ByteArray(code.size) { 0xff.toByte() }
        when (arch) {
            CodeArch.ARM64 -> maskArm64(code, mask)
            CodeArch.THUMB -> maskThumb(code, mask, address)
            CodeArch.ARM -> maskArm(code, mask, address)
        }
        val bytes = ByteArray(code.size) { (code[it].toInt() and mask[it].toInt()).toByte() }
        return MaskedCode(bytes, mask)
    }

    // ---------------- arm64 ----------------

    private fun maskArm64(code: ByteArray, mask: ByteArray) {
        // ADRP 写过的寄存器：随后以它为基址的立即数是 :lo12: 低位，换一次构建就变
        var adrpRegs = 0
        var i = 0
        while (i + 4 <= code.size) {
            val insn = u32(code, i)
            val keep: Int = when {
                // ADR / ADRP：immlo(29..30) + immhi(5..23)
                (insn and 0x1F000000) == 0x10000000 -> {
                    if (insn < 0) adrpRegs = adrpRegs or (1 shl (insn and 0x1f))
                    0x9F00001F.toInt()
                }
                // B / BL：imm26
                (insn and 0x7C000000) == 0x14000000 -> 0xFC000000.toInt()
                // B.cond：imm19
                (insn and 0xFF000010.toInt()) == 0x54000000 -> 0xFF00001F.toInt()
                // CBZ / CBNZ：imm19
                (insn and 0x7E000000) == 0x34000000 -> 0xFF00001F.toInt()
                // TBZ / TBNZ：imm14
                (insn and 0x7E000000) == 0x36000000 -> 0xFFF8001F.toInt()
                // LDR / LDRSW / PRFM / LDR(SIMD) literal：imm19
                (insn and 0x3B000000) == 0x18000000 -> 0xFF00001F.toInt()
                // ADD 立即数（32 / 64 位），基址是 ADRP 的目标
                (insn and 0x7F800000) == 0x11000000 && adrpRegs.hasReg((insn ushr 5) and 0x1f) -> 0xFFC003FF.toInt()
                // LDR / STR 无符号立即数（各种宽度），基址是 ADRP 的目标
                (insn and 0x3B000000) == 0x39000000 && adrpRegs.hasReg((insn ushr 5) and 0x1f) -> 0xFFC003FF.toInt()
                else -> -1
            }
            applyKeep32(mask, i, keep)
            i += 4
        }
    }

    private fun Int.hasReg(r: Int) = (this and (1 shl r)) != 0

    // ---------------- Thumb / Thumb-2 ----------------

    private fun maskThumb(code: ByteArray, mask: ByteArray, address: Long) {
        // 文字池：不当指令解码，内容（地址相关）通配
        val data = BitSet(code.size)
        var i = 0
        while (i + 2 <= code.size) {
            if (data.get(i)) {
                i += 2
                continue
            }
            val hw1 = u16(code, i)
            val top5 = hw1 ushr 11
            val is32 = top5 == 0b11101 || top5 == 0b11110 || top5 == 0b11111
            if (!is32) {
                val keep = when {
                    // B<cond> T1（cond 1110 / 1111 是 UDF / SVC）
                    (hw1 and 0xF000) == 0xD000 && ((hw1 ushr 8) and 0xF) < 0xE -> 0xFF00
                    // B T2
                    (hw1 and 0xF800) == 0xE000 -> 0xF800
                    // CBZ / CBNZ
                    (hw1 and 0xF500) == 0xB100 -> 0xFD07
                    // ADR T1
                    (hw1 and 0xF800) == 0xA000 -> 0xFF00
                    // LDR (literal) T1：imm8 * 4
                    (hw1 and 0xF800) == 0x4800 -> {
                        val pool = align4(address + i + 4) + (hw1 and 0xff) * 4 - address
                        markPool(pool, 4, code.size, data, mask)
                        0xFF00
                    }
                    else -> 0xFFFF
                }
                applyKeep16(mask, i, keep)
                i += 2
                continue
            }
            if (i + 4 > code.size) break
            val hw2 = u16(code, i + 2)
            var keep1 = 0xFFFF
            var keep2 = 0xFFFF
            when {
                // B.W / B<cond>.W / BL / BLX：hw1 11110xxxxxxxxxxx，hw2 第 15 位为 1。偏移散在两半里
                (hw1 and 0xF800) == 0xF000 && (hw2 and 0x8000) != 0 -> {
                    keep1 = 0xF800
                    keep2 = 0xD000
                }
                // LDR / LDRB / LDRH / LDRSB / LDRSH (literal)：Rn = PC
                (hw1 and 0xFE0F) == 0xF80F || (hw1 and 0xFE0F) == 0xF90F -> {
                    val up = (hw1 and 0x80) != 0
                    if (up && (hw1 and 0xFF7F) == 0xF85F) {
                        val pool = align4(address + i + 4) + (hw2 and 0xfff) - address
                        markPool(pool, 4, code.size, data, mask)
                    }
                    keep1 = 0xFF7F
                    keep2 = 0xF000
                }
                // VLDR (literal)：常量本身不变，但偏移会变；池里的数据不当指令解码
                (hw1 and 0xFF3F) == 0xED1F && (hw2 and 0x0E00) == 0x0A00 -> {
                    val up = (hw1 and 0x80) != 0
                    if (up) {
                        val pool = align4(address + i + 4) + (hw2 and 0xff) * 4 - address
                        val size = if ((hw2 and 0x100) != 0) 8 else 4
                        markData(pool, size, code.size, data)
                    }
                    keep1 = 0xFF7F
                    keep2 = 0xFF00
                }
                // MOVW / MOVT（PIC 里装 PC 相对偏移）：imm4:i:imm3:imm8
                (hw1 and 0xFBF0) == 0xF240 || (hw1 and 0xFBF0) == 0xF2C0 -> {
                    keep1 = 0xFBF0
                    keep2 = 0x8F00
                }
                // ADR T2 / T3（SUBW / ADDW Rd, PC, #imm12）
                (hw1 and 0xFBFF) == 0xF2AF || (hw1 and 0xFBFF) == 0xF20F -> {
                    keep1 = 0xFBFF
                    keep2 = 0x8F00
                }
            }
            applyKeep16(mask, i, keep1)
            applyKeep16(mask, i + 2, keep2)
            i += 4
        }
    }

    // ---------------- ARM (A32) ----------------

    private fun maskArm(code: ByteArray, mask: ByteArray, address: Long) {
        val data = BitSet(code.size)
        var i = 0
        while (i + 4 <= code.size) {
            if (data.get(i)) {
                i += 4
                continue
            }
            val insn = u32(code, i)
            val keep: Int = when {
                // BLX imm（cond = 1111）
                (insn and 0xFE000000.toInt()) == 0xFA000000.toInt() -> 0xFE000000.toInt()
                // B / BL
                (insn and 0x0E000000) == 0x0A000000 -> 0xFF000000.toInt()
                // LDR (literal)
                (insn and 0x0F7F0000) == 0x051F0000 -> {
                    if ((insn and 0x00800000) != 0) {
                        val pool = address + i + 8 + (insn and 0xfff) - address
                        markPool(pool, 4, code.size, data, mask)
                    }
                    0xFF7FF000.toInt()
                }
                // MOVW / MOVT
                (insn and 0x0FF00000) == 0x03000000 || (insn and 0x0FF00000) == 0x03400000 -> 0xFFF0F000.toInt()
                else -> -1
            }
            applyKeep32(mask, i, keep)
            i += 4
        }
    }

    // ---------------- 工具 ----------------

    private fun markPool(offset: Long, size: Int, codeSize: Int, data: BitSet, mask: ByteArray) {
        if (offset < 0 || offset + size > codeSize) return
        val o = offset.toInt()
        data.set(o, o + size)
        for (k in o until o + size) mask[k] = 0
    }

    private fun markData(offset: Long, size: Int, codeSize: Int, data: BitSet) {
        if (offset < 0 || offset + size > codeSize) return
        data.set(offset.toInt(), offset.toInt() + size)
    }

    private fun align4(v: Long) = v and 3L.inv()

    private fun applyKeep32(mask: ByteArray, at: Int, keep: Int) {
        for (k in 0 until 4) mask[at + k] = (mask[at + k].toInt() and (keep ushr (8 * k))).toByte()
    }

    private fun applyKeep16(mask: ByteArray, at: Int, keep: Int) {
        mask[at] = (mask[at].toInt() and keep).toByte()
        mask[at + 1] = (mask[at + 1].toInt() and (keep ushr 8)).toByte()
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}

package com.fixcode.intellij.lua.debugger.android.elf

import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * `.gnu_debugdata`（MiniDebugInfo）：xz 压缩的一个小 ELF，里面只有 `.symtab`，
 * 记着 .dynsym 之外的函数符号。Android 平台库和一些带 `--keep-symbols` 打包的 so 有它，
 * 设备上的 so 被 strip 过也保留，所以没有未裁剪 so 时可以直接从设备 so 里拿到 lua_* 的偏移。
 */
object MiniDebugInfo {
    /** 解压后的上限：正常只有几 MB，防止坏数据把内存撑爆。 */
    private const val MAX_SIZE = 256L * 1024 * 1024

    /** 解压 [elf] 的 `.gnu_debugdata`；没有这个节返回 null，数据坏了抛 [ElfFormatException]。 */
    fun open(elf: ElfFile): ElfFile? {
        val section = elf.section(".gnu_debugdata") ?: return null
        if (section.size <= 0 || section.size > Int.MAX_VALUE) return null
        val compressed = elf.readBytes(section.offset, section.size.toInt())
        return ElfFile.fromBytes(decompress(compressed), "${elf.path}!.gnu_debugdata")
    }

    fun decompress(xz: ByteArray): ByteArray = try {
        XZInputStream(ByteArrayInputStream(xz)).use { input ->
            val out = ByteArrayOutputStream(xz.size * 4)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_SIZE) throw ElfFormatException(".gnu_debugdata 解压后超过 ${MAX_SIZE / 1024 / 1024} MB，不像 MiniDebugInfo")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: ElfFormatException) {
        throw e
    } catch (e: Exception) {
        throw ElfFormatException(".gnu_debugdata 解压失败：${e.message}")
    }
}

package com.fixcode.intellij.lua.debugger.android.elf

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import java.util.concurrent.CancellationException

/** 一个要找的函数：名字、参考 so 里的地址（Thumb 带最低位）、通配后的完整函数体特征。 */
class FunctionSignature(
    val name: String,
    /** 参考 so 里的符号值，只用来做冲突检查和日志。 */
    val referenceValue: Long,
    /** 从入口到函数结尾（最多 [SignatureScanner.MAX_LENGTH] 字节）的特征。 */
    val code: MaskedCode,
)

sealed class SignatureMatch {
    abstract val name: String

    /** 唯一匹配。[value] 是目标 so 里的符号值（Thumb 带最低位），[length] 是最后用到的特征长度。 */
    data class Found(
        override val name: String,
        val value: Long,
        val length: Int,
        /** true = 特征本身不唯一 / 太短，按前后两个已匹配函数的相对位置推算并校验了整个函数体。 */
        val byNeighbours: Boolean = false,
    ) : SignatureMatch()

    data class NotFound(override val name: String, val reason: String) : SignatureMatch()
}

/**
 * 在目标 so（设备上裁剪过的 libUnreal.so）的可执行段里搜特征码。
 *
 * libUnreal.so 的代码段常有一两百 MB，所有函数一起只扫一遍：
 * 每个特征选一个全固定位的 4 字节「锚点」，扫描时按对齐步长取 4 字节，先查位图（绝大部分直接跳过），
 * 命中再查锚点表、在内存里校验前 [DEFAULT_LENGTH] 字节，并顺手把候选位置后面的字节留下来，
 * 之后「加长特征」只在这些候选上比，不用重扫。
 *
 * 判定只接受唯一匹配：默认 64 字节；多处匹配就把特征加长一倍再比，直到函数结尾；仍然不唯一、
 * 或者一处都没有（加长只会更少），这个函数就算找不到。
 * 太短的函数（两三条指令）再按前后相邻函数的相对位置推算一次（见 [inferFromNeighbours]）。
 */
class SignatureScanner(
    private val arch: CodeArch,
    private val cancelled: () -> Boolean = { false },
) {
    /** 目标 so 的一段可执行代码：虚拟地址、文件偏移、长度，按偏移读。 */
    class CodeRegion(val vaddr: Long, val fileOffset: Long, val size: Long, val reader: (offset: Long, length: Int) -> ByteArray)

    private class Candidate(val vaddr: Long, val bytes: ByteArray)

    private class Pending(val sig: FunctionSignature, val anchorOffset: Int, val firstLength: Int) {
        val candidates = ArrayList<Candidate>()
        var total = 0
    }

    /**
     * [extraAnchors]：别处已经确定的「参考地址 → 目标地址」（例如上一轮找到的函数），推算相邻函数时一起用。
     */
    fun search(
        signatures: List<FunctionSignature>,
        regions: List<CodeRegion>,
        extraAnchors: List<Pair<Long, Long>> = emptyList(),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Map<String, SignatureMatch> {
        val results = LinkedHashMap<String, SignatureMatch>()
        val pending = ArrayList<Pending>()
        for (sig in signatures) {
            val first = minOf(DEFAULT_LENGTH, sig.code.length)
            if (first < MIN_LENGTH) {
                results[sig.name] = SignatureMatch.NotFound(sig.name, "参考 so 里的函数太短（${sig.code.length} 字节），特征不可靠")
                continue
            }
            val anchor = pickAnchor(sig.code, first)
            if (anchor < 0) {
                results[sig.name] = SignatureMatch.NotFound(sig.name, "函数开头全是和地址有关的指令，做不出特征")
                continue
            }
            pending += Pending(sig, anchor, first)
        }
        if (pending.isNotEmpty()) scan(pending, regions, progress)
        for (p in pending) results[p.sig.name] = decide(p)
        inferFromNeighbours(results, signatures, regions, extraAnchors)
        rejectSharedAddresses(results, signatures)
        return signatures.mapNotNull { s -> results[s.name]?.let { s.name to it } }.toMap(LinkedHashMap())
    }

    /** 选锚点：全固定位、不是常见指令（NOP、RET、函数序言），尽量靠后一点、字节种类多一点。 */
    private fun pickAnchor(code: MaskedCode, len: Int): Int {
        var best = -1
        var bestScore = -1
        var off = 0
        while (off + 4 <= len) {
            if (code.isFixedWord(off)) {
                val w = code.wordAt(off)
                if (w !in COMMON_WORDS) {
                    val distinct = (0 until 4).map { (w ushr (8 * it)) and 0xff }.toSet().size
                    // 前两条常是开栈帧，几乎每个函数都一样
                    val score = distinct * 4 + (if (off >= 8) 3 else 0) + (if (off >= 4) 1 else 0)
                    if (score > bestScore) {
                        bestScore = score
                        best = off
                    }
                }
            }
            off += arch.alignment
        }
        return best
    }

    private fun scan(pending: List<Pending>, regions: List<CodeRegion>, progress: (Long, Long) -> Unit) {
        val bloom = BitSet(1 shl BLOOM_BITS)
        val byAnchor = HashMap<Int, MutableList<Pending>>()
        for (p in pending) {
            val w = p.sig.code.wordAt(p.anchorOffset)
            bloom.set(hash(w))
            byAnchor.getOrPut(w) { ArrayList() } += p
        }
        val back = pending.maxOf { it.anchorOffset }
        val forward = MAX_LENGTH
        val total = regions.sumOf { it.size }
        var done = 0L
        for (region in regions) {
            var chunkStart = 0L
            while (chunkStart < region.size) {
                if (cancelled()) throw CancellationException("已取消特征码搜索")
                val chunkEnd = minOf(region.size, chunkStart + CHUNK)
                // 窗口前后多读一点：锚点在特征中间，候选还要留下后面的字节
                val winStart = maxOf(0L, chunkStart - back)
                val winEnd = minOf(region.size, chunkEnd + forward)
                val window = region.reader(region.fileOffset + winStart, (winEnd - winStart).toInt())
                val buf = ByteBuffer.wrap(window).order(ByteOrder.LITTLE_ENDIAN)
                // 按虚拟地址对齐
                var pos = chunkStart
                val mis = ((region.vaddr + pos) % arch.alignment).toInt()
                if (mis != 0) pos += arch.alignment - mis
                while (pos < chunkEnd) {
                    val local = (pos - winStart).toInt()
                    if (local + 4 > window.size) break
                    val w = buf.getInt(local)
                    if (bloom.get(hash(w))) {
                        byAnchor[w]?.let { list -> for (p in list) check(p, region, window, winStart, pos) }
                    }
                    pos += arch.alignment
                }
                done += chunkEnd - chunkStart
                progress(done, total)
                chunkStart = chunkEnd
            }
        }
    }

    private fun check(p: Pending, region: CodeRegion, window: ByteArray, winStart: Long, anchorPos: Long) {
        val start = anchorPos - p.anchorOffset
        if (start < 0) return
        val local = (start - winStart).toInt()
        if (local < 0 || local + p.firstLength > window.size) return
        if (!p.sig.code.matches(window, local, p.firstLength)) return
        p.total++
        if (p.candidates.size >= MAX_CANDIDATES) return
        val keep = minOf(p.sig.code.length, window.size - local)
        p.candidates += Candidate(region.vaddr + start, window.copyOfRange(local, local + keep))
    }

    private fun decide(p: Pending): SignatureMatch {
        val name = p.sig.name
        if (p.total == 0) return SignatureMatch.NotFound(name, "目标 so 里没有匹配（可能这次构建改了这个函数，或者参考 so 的 Lua 版本 / 编译选项不同）")
        if (p.total > MAX_CANDIDATES) return SignatureMatch.NotFound(name, "匹配 ${p.total} 处，特征太普通")
        var len = p.firstLength
        var alive: List<Candidate> = p.candidates
        while (true) {
            val l = len
            alive = alive.filter { c -> c.bytes.size >= l && p.sig.code.matches(c.bytes, 0, l) }
            if (alive.size == 1) return SignatureMatch.Found(name, thumbBit(alive[0].vaddr), len)
            if (alive.isEmpty()) return SignatureMatch.NotFound(name, "加长到 $len 字节后没有匹配")
            if (len >= p.sig.code.length) {
                return SignatureMatch.NotFound(name, "匹配 ${alive.size} 处，加长到函数结尾（${p.sig.code.length} 字节）仍不唯一")
            }
            len = minOf(len * 2, p.sig.code.length)
        }
    }

    /**
     * 太短或不唯一的函数（lua_status 这种两三条指令的）按相邻函数推算：同一个 .o 里的函数在两次构建中相对位置不变。
     * 参考 so 里它前后各有一个已经唯一匹配的函数，而且这两个函数在目标 so 里的距离和参考 so 里一样，
     * 说明中间这段布局没变，就按同样的偏移去取，并且整个函数体（通配后）都要对得上才接受。
     */
    private fun inferFromNeighbours(
        results: MutableMap<String, SignatureMatch>,
        signatures: List<FunctionSignature>,
        regions: List<CodeRegion>,
        extraAnchors: List<Pair<Long, Long>>,
    ) {
        val clean = 1L.inv()
        val anchors = (signatures.mapNotNull { s ->
            (results[s.name] as? SignatureMatch.Found)?.takeIf { !it.byNeighbours }?.let { (s.referenceValue and clean) to (it.value and clean) }
        } + extraAnchors.map { (it.first and clean) to (it.second and clean) }).distinct().sortedBy { it.first }
        if (anchors.isEmpty()) return
        for (sig in signatures) {
            if (results[sig.name] is SignatureMatch.Found) continue
            val ref = sig.referenceValue and clean
            val prev = anchors.lastOrNull { it.first < ref }
            val next = anchors.firstOrNull { it.first > ref }
            // 前后两个锚点间距不变；或者紧挨着的邻居（同一个 .o 里，中间没有能变的东西）
            val base = when {
                prev != null && next != null && next.second - prev.second == next.first - prev.first -> prev
                prev != null && ref - prev.first <= NEAR -> prev
                next != null && next.first - ref <= NEAR -> next
                else -> null
            } ?: continue
            val candidate = base.second + (ref - base.first)
            val region = regions.firstOrNull { candidate >= it.vaddr && candidate + sig.code.length <= it.vaddr + it.size } ?: continue
            val bytes = try {
                region.reader(region.fileOffset + (candidate - region.vaddr), sig.code.length)
            } catch (_: Exception) {
                continue
            }
            if (bytes.size == sig.code.length && sig.code.matches(bytes, 0, bytes.size)) {
                results[sig.name] = SignatureMatch.Found(sig.name, thumbBit(candidate), sig.code.length, byNeighbours = true)
            }
        }
    }

    /**
     * 两个不同的函数匹配到同一个地址：参考 so 里它们也是同一个地址（链接器合并了相同函数）就没问题，
     * 否则说明特征认错了，两个都不要。
     */
    private fun rejectSharedAddresses(results: MutableMap<String, SignatureMatch>, signatures: List<FunctionSignature>) {
        val refValue = signatures.associate { it.name to it.referenceValue }
        val byValue = results.values.filterIsInstance<SignatureMatch.Found>().groupBy { it.value }
        for ((_, group) in byValue) {
            if (group.size < 2) continue
            if (group.map { refValue[it.name] }.toSet().size == 1) continue
            for (m in group) {
                results[m.name] = SignatureMatch.NotFound(m.name, "和 ${group.filter { it !== m }.joinToString { it.name }} 匹配到同一个地址，特征不可靠")
            }
        }
    }

    private fun thumbBit(vaddr: Long) = if (arch == CodeArch.THUMB) vaddr or 1L else vaddr

    companion object {
        const val DEFAULT_LENGTH = 64
        /** 再短就太容易撞上别的函数。 */
        const val MIN_LENGTH = 12
        /** 最长比到 4 KB；大函数前 4 KB 还不唯一基本说明有两份同样的代码。 */
        const val MAX_LENGTH = 4096
        private const val MAX_CANDIDATES = 64
        /** 单边推算只信这么近的邻居。 */
        private const val NEAR = 256L
        private const val CHUNK = 8L * 1024 * 1024
        private const val BLOOM_BITS = 22

        private val COMMON_WORDS = setOf(
            0x00000000, -1,
            0xD503201F.toInt(), // arm64 NOP
            0xD65F03C0.toInt(), // arm64 RET
            0xA9BF7BFD.toInt(), // stp x29, x30, [sp, #-16]!
            0x910003FD,         // mov x29, sp
            0xD503233F.toInt(), // paciasp
            0xD50323BF.toInt(), // autiasp
            0xD503245F.toInt(), // bti c
            0xE12FFF1E.toInt(), // bx lr（A32）
            0x46C046C0,         // Thumb nop; nop
        )

        private fun hash(w: Int): Int = ((w * -0x61c88647) ushr (32 - BLOOM_BITS))

        /** 参考 so 的函数在哪种指令集下：arm64 → ARM64；armv7 看符号值最低位（1 = Thumb）。 */
        fun archOf(machine: Int, symbolValue: Long): CodeArch? = when (machine) {
            ElfFile.EM_AARCH64 -> CodeArch.ARM64
            ElfFile.EM_ARM -> if ((symbolValue and 1L) != 0L) CodeArch.THUMB else CodeArch.ARM
            else -> null
        }
    }
}

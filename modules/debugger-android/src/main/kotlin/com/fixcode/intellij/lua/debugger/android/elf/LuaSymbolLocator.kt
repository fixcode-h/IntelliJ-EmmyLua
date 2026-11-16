package com.fixcode.intellij.lua.debugger.android.elf

import java.io.File
import java.util.Properties
import java.util.concurrent.CancellationException

/** 用户给的符号线索：运行配置里指定的 so、设置里的符号目录、参考 so。 */
data class SymbolSearchOptions(
    /** 运行配置里指定的未裁剪 so；为空表示自动。 */
    val configuredFile: File? = null,
    /** 设置里的符号目录（已展开 $PROJECT_DIR$）。 */
    val symbolDirs: List<File> = emptyList(),
    /** 设置 / 运行配置里的参考 so：同一工程以前任意一次构建的未裁剪 libUnreal.so，做特征码用。 */
    val referenceSo: File? = null,
    /** 设备 Build ID 未知时按文件名在符号目录里找的 so 名。 */
    val moduleName: String = "libUnreal.so",
)

/**
 * 设备上的 so：Build ID（附加前从设备读开头几 KB 得到，可能读不到）和按需打开的 ELF。
 * [open] 经 adb 按偏移读设备文件，读 .dynsym 只要几次往返，特征码搜索会把可执行段整个读一遍。
 */
class DeviceLibrary(val buildId: String?, val description: String, val open: () -> ElfFile)

enum class AttemptStatus { USED, PARTIAL, FAILED, SKIPPED }

/** 一级来源的结果，给日志、环境检查和失败提示用。 */
data class SymbolAttempt(val source: SymbolSource, val status: AttemptStatus, val detail: String)

class SymbolLookupResult(
    /** 拿全必需函数的表；都失败时为 null。 */
    val table: LuaSymbolTable?,
    val attempts: List<SymbolAttempt>,
    /** 都失败时还缺的必需函数（最后一级能看到的）。 */
    val missingRequired: List<String> = emptyList(),
) {
    val ok: Boolean get() = table != null

    /** 给用户看的失败说明：每级为什么不行、缺哪些函数、怎么办。 */
    fun failureMessage(): String = buildString {
        append("找不到调试需要的 Lua 函数。")
        if (missingRequired.isNotEmpty()) {
            append("缺少：").append(missingRequired.take(12).joinToString("、"))
            if (missingRequired.size > 12) append(" 等 ${missingRequired.size} 个")
            append("。")
        }
        for (a in attempts) append("\n· ").append(a.source.label).append("：").append(a.detail)
        append("\n可以：① 保留这次构建的未裁剪 libUnreal.so（Binaries/Android 或 Intermediate/Android），在运行配置里指定；")
        append("② 在设置里把构建归档目录加进符号目录（按 Build ID 自动匹配）；")
        append("③ 在设置里指定参考 so（同一工程以前任意一次构建的未裁剪 libUnreal.so，同 ABI、同 Lua 版本），按特征码查找。")
    }
}

/**
 * 按顺序逐级找 Lua 函数偏移，任何一级拿全必需函数就用它（设计文档 7.2 第 2 点）：
 *
 * 0. 运行配置里指定的未裁剪 so（Build ID 和设备一致，或设备 Build ID 未知）；
 * 1. 符号目录里 Build ID 相同的 ELF（递归扫描 + `.build-id/xx/yyyy.debug`）；
 * 2. 设备 so 自带的 `.dynsym` 和 `.gnu_debugdata`（MiniDebugInfo）；
 * 3. 特征码：从参考 so 取函数开头的机器码，通配地址相关的立即数，在设备 so 的代码段里找唯一匹配。
 *    第 2 级找到的函数直接沿用，只搜剩下的。
 *
 * 第 2、3 级的结果按 Build ID 缓存（特征码按「目标 + 参考」两个 Build ID），第二次附加不再读设备、不再搜索。
 */
class LuaSymbolLocator(
    private val resolver: LuaSymbolResolver,
    private val cacheDir: File?,
    private val dirIndex: SymbolDirIndex = SymbolDirIndex(cacheDir),
) {
    constructor(cacheDir: File?) : this(LuaSymbolResolver(cacheDir), cacheDir)

    fun locate(
        options: SymbolSearchOptions,
        device: DeviceLibrary?,
        log: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): SymbolLookupResult {
        val attempts = ArrayList<SymbolAttempt>()
        val targetId = device?.buildId?.lowercase()
        var lastMissing: List<String> = emptyList()

        fun done(table: LuaSymbolTable, status: AttemptStatus = AttemptStatus.USED): SymbolLookupResult {
            attempts += SymbolAttempt(table.source, status, table.describe())
            log("符号来源：${table.source.label}（${table.sourcePath}）")
            return SymbolLookupResult(table, attempts)
        }

        // 0. 运行配置里指定的文件
        options.configuredFile?.let { file ->
            try {
                val table = resolver.resolve(file)
                when {
                    targetId != null && !table.buildId.equals(targetId, ignoreCase = true) -> attempts += SymbolAttempt(
                        SymbolSource.LOCAL_FILE, AttemptStatus.FAILED,
                        "${file.name} 和设备上的不是同一次构建（本地 Build ID ${table.buildId.take(12)}…，设备 ${targetId.take(12)}…）",
                    )
                    table.usable -> return done(table)
                    else -> {
                        attempts += SymbolAttempt(SymbolSource.LOCAL_FILE, AttemptStatus.FAILED, table.describe())
                        lastMissing = table.missingRequired
                    }
                }
            } catch (e: Exception) {
                attempts += SymbolAttempt(SymbolSource.LOCAL_FILE, AttemptStatus.FAILED, e.message ?: "读取 ${file.name} 失败")
            }
        }
        checkCancelled(cancelled)

        // 1. 符号目录
        val dirs = options.symbolDirs.filter { it.isDirectory }
        if (targetId != null) {
            if (dirs.isEmpty()) {
                attempts += SymbolAttempt(SymbolSource.SYMBOL_DIR, AttemptStatus.SKIPPED, "设置里没有可用的符号目录")
            } else {
                val files = dirIndex.find(dirs, targetId, cancelled = cancelled)
                checkCancelled(cancelled)
                var used: LuaSymbolTable? = null
                for (f in files) {
                    val table = try {
                        resolver.resolve(f)
                    } catch (_: Exception) {
                        continue
                    }
                    val t = table.copy(source = SymbolSource.SYMBOL_DIR, sourceDetail = f.path)
                    if (t.usable) {
                        used = t
                        break
                    }
                    lastMissing = t.missingRequired
                    attempts += SymbolAttempt(SymbolSource.SYMBOL_DIR, AttemptStatus.FAILED, "${f.path}：${t.describe()}")
                }
                if (used != null) return done(used)
                if (files.isEmpty()) {
                    attempts += SymbolAttempt(SymbolSource.SYMBOL_DIR, AttemptStatus.FAILED,
                        "${dirs.size} 个目录里没有 Build ID 为 ${targetId.take(12)}… 的未裁剪 so")
                }
            }
        } else if (options.configuredFile == null) {
            // 设备 Build ID 未知（没连设备、应用没装）：退回按文件名找最新的一个，由 agent 附加时校验
            val candidate = resolver.findCandidates(dirs, options.moduleName).firstOrNull()
            if (candidate != null) {
                try {
                    val table = resolver.resolve(candidate)
                    if (table.usable) {
                        return done(table.copy(sourceDetail = "设备 Build ID 未知，用最新的 ${candidate.path}，附加时由 agent 校验"))
                    }
                    attempts += SymbolAttempt(SymbolSource.LOCAL_FILE, AttemptStatus.FAILED, table.describe())
                } catch (e: Exception) {
                    attempts += SymbolAttempt(SymbolSource.LOCAL_FILE, AttemptStatus.FAILED, e.message ?: "读取失败")
                }
            } else {
                attempts += SymbolAttempt(SymbolSource.SYMBOL_DIR, AttemptStatus.FAILED,
                    if (dirs.isEmpty()) "设置里没有可用的符号目录" else "符号目录里没有未裁剪的 ${options.moduleName}")
            }
        }
        checkCancelled(cancelled)

        if (device == null) {
            attempts += SymbolAttempt(SymbolSource.DEVICE_SYMBOLS, AttemptStatus.SKIPPED, "读不到设备上的 so（没连设备，或应用没有安装 / 没在运行）")
            if (options.referenceSo != null) {
                attempts += SymbolAttempt(SymbolSource.SIGNATURE, AttemptStatus.SKIPPED, "需要设备上的 so 才能搜特征码")
            }
            return SymbolLookupResult(null, attempts, lastMissing)
        }

        // 2. 设备 so 自带的符号；3. 特征码。设备 so 只打开一次
        var deviceElf: ElfFile? = null
        try {
            val partial = deviceSymbols(device, targetId, { deviceElf ?: device.open().also { deviceElf = it } }, log)
            if (partial.table != null && partial.table.usable) return done(partial.table)
            attempts += partial.attempt
            partial.table?.let { lastMissing = it.missingRequired }
            checkCancelled(cancelled)

            val ref = options.referenceSo
            if (ref == null) {
                attempts += SymbolAttempt(SymbolSource.SIGNATURE, AttemptStatus.SKIPPED, "没有指定参考 so")
                return SymbolLookupResult(null, attempts, lastMissing)
            }
            val sig = signatureTable(ref, device, targetId, partial.table, { deviceElf ?: device.open().also { deviceElf = it } }, log, cancelled)
            if (sig.table != null && sig.table.usable) return done(sig.table)
            attempts += sig.attempt
            sig.table?.let { lastMissing = it.missingRequired }
            return SymbolLookupResult(null, attempts, lastMissing)
        } finally {
            try {
                deviceElf?.close()
            } catch (_: Exception) {
            }
        }
    }

    private class Partial(val table: LuaSymbolTable?, val attempt: SymbolAttempt)

    // ---------------- 第 2 级：设备 so 自带的符号 ----------------

    private fun deviceSymbols(device: DeviceLibrary, targetId: String?, open: () -> ElfFile, log: (String) -> Unit): Partial {
        val cacheKey = targetId?.let { "lua-device-$it" }
        cacheKey?.let { loadDerived(it) }?.let { cached ->
            return Partial(cached, SymbolAttempt(SymbolSource.DEVICE_SYMBOLS, AttemptStatus.PARTIAL, cached.describe() + "（缓存）"))
        }
        return try {
            log("读取设备 so 自带的符号（.dynsym / .gnu_debugdata）")
            val elf = open()
            val buildId = elf.buildId() ?: targetId
                ?: return Partial(null, SymbolAttempt(SymbolSource.DEVICE_SYMBOLS, AttemptStatus.FAILED, "设备上的 so 没有 Build ID"))
            val found = HashMap<String, MutableSet<Long>>()
            LuaSymbolResolver.collectLuaSymbols(elf, listOf(".dynsym"), found)
            val fromDynsym = found.keys.count(LuaApiSymbols::isApi)
            var fromMini = 0
            var miniNote = "没有 .gnu_debugdata"
            try {
                MiniDebugInfo.open(elf)?.use { mini ->
                    val before = found.keys.count(LuaApiSymbols::isApi)
                    LuaSymbolResolver.collectLuaSymbols(mini, listOf(".symtab"), found)
                    fromMini = found.keys.count(LuaApiSymbols::isApi) - before
                    miniNote = "MiniDebugInfo $fromMini 个"
                }
            } catch (e: ElfFormatException) {
                miniNote = e.message ?: ".gnu_debugdata 读取失败"
            }
            val detail = ".dynsym $fromDynsym 个，$miniNote"
            val table = LuaSymbolResolver.buildTable(found, buildId, elf.abi, device.description, SymbolSource.DEVICE_SYMBOLS, detail)
            cacheKey?.let { saveDerived(it, table, emptyMap()) }
            Partial(table, SymbolAttempt(SymbolSource.DEVICE_SYMBOLS, if (table.functions.isEmpty()) AttemptStatus.FAILED else AttemptStatus.PARTIAL, table.describe()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Partial(null, SymbolAttempt(SymbolSource.DEVICE_SYMBOLS, AttemptStatus.FAILED, "读取设备上的 so 失败：${e.message}"))
        }
    }

    // ---------------- 第 3 级：特征码 ----------------

    private fun signatureTable(
        refFile: File,
        device: DeviceLibrary,
        targetId: String?,
        exact: LuaSymbolTable?,
        open: () -> ElfFile,
        log: (String) -> Unit,
        cancelled: () -> Boolean,
    ): Partial {
        fun fail(msg: String) = Partial(null, SymbolAttempt(SymbolSource.SIGNATURE, AttemptStatus.FAILED, msg))
        if (!refFile.isFile) return fail("参考 so 不存在：${refFile.path}")
        val refTable = try {
            resolver.resolve(refFile)
        } catch (e: Exception) {
            return fail("参考 so 读不了：${e.message}")
        }
        val refId = refTable.buildId
        val cacheKey = if (targetId != null) "lua-sig-$targetId-$refId" else null
        cacheKey?.let { loadDerived(it) }?.let { cached ->
            log("特征码结果来自缓存")
            return Partial(cached, SymbolAttempt(SymbolSource.SIGNATURE, if (cached.usable) AttemptStatus.USED else AttemptStatus.FAILED, cached.describe() + "（缓存）"))
        }
        if (targetId != null && refId.equals(targetId, ignoreCase = true)) {
            // 参考 so 就是这次构建：直接用它的符号
            return Partial(refTable.copy(source = SymbolSource.SYMBOL_DIR, sourceDetail = "参考 so 就是这次构建"), SymbolSource.SYMBOL_DIR.let {
                SymbolAttempt(it, AttemptStatus.USED, refTable.describe())
            })
        }
        return try {
            val target = open()
            val buildId = target.buildId() ?: targetId ?: return fail("设备上的 so 没有 Build ID")
            ElfFile.open(refFile).use { ref ->
                if (ref.machine != target.machine || ref.is64 != target.is64) {
                    return fail("参考 so 是 ${ref.abi ?: "未知架构"}，设备上的是 ${target.abi ?: "未知架构"}：要用同一 ABI 的参考 so")
                }
                val wanted = (LuaApiSymbols.requiredFor(refTable.luaVersion) + LuaApiSymbols.optionalFor(refTable.luaVersion) +
                    LuaApiSymbols.hookTargets + LuaApiSymbols.critical).toSet()
                val have = exact?.functions.orEmpty()
                val sigs = buildSignatures(ref, refTable, wanted.filter { it !in have && it in refTable.functions })
                val regions = target.executableSegments().map { seg ->
                    SignatureScanner.CodeRegion(seg.vaddr, seg.offset, seg.fileSize) { off, len -> target.readBytes(off, len) }
                }
                if (regions.isEmpty()) return fail("设备上的 so 没有可执行段")
                val totalMb = regions.sumOf { it.size } / (1024 * 1024)
                log("按特征码搜索 ${sigs.values.sumOf { it.size }} 个函数（设备 so 代码段约 $totalMb MB）")
                val started = System.currentTimeMillis()
                val matches = LinkedHashMap<String, SignatureMatch>()
                for ((arch, list) in sigs) {
                    val scanner = SignatureScanner(arch, cancelled)
                    val first = scanner.search(list, regions)
                    matches += first
                    // 没找到的（多半是两三条指令的小函数）：再搜参考 so 里紧挨着它的函数（含 static），按相邻位置推算
                    val missing = list.filter { first[it.name] !is SignatureMatch.Found }
                    if (missing.isNotEmpty()) {
                        val helpers = neighbourSignatures(ref, missing, list.map { it.referenceValue }.toSet(), arch)
                        if (helpers.isNotEmpty()) {
                            val anchors = list.mapNotNull { s -> (first[s.name] as? SignatureMatch.Found)?.let { s.referenceValue to it.value } }
                            val second = scanner.search(helpers + missing, regions, anchors)
                            for (s in missing) second[s.name]?.let { matches[s.name] = it }
                        }
                    }
                }
                val found = HashMap<String, MutableSet<Long>>()
                have.forEach { (k, v) -> found[k] = linkedSetOf(v) }
                val missReasons = LinkedHashMap<String, String>()
                for (m in matches.values) when (m) {
                    is SignatureMatch.Found -> found[m.name] = linkedSetOf(m.value)
                    is SignatureMatch.NotFound -> missReasons[m.name] = m.reason
                }
                val foundBySig = matches.values.count { it is SignatureMatch.Found }
                val detail = buildString {
                    append("参考 so ${refFile.name}（Build ID ${refId.take(12)}…），特征码找到 $foundBySig 个")
                    if (have.isNotEmpty()) append("，设备符号 ${have.size} 个")
                    append("，用时 ${(System.currentTimeMillis() - started) / 100 / 10.0} 秒")
                }
                val table = LuaSymbolResolver.buildTable(found, buildId, target.abi, device.description, SymbolSource.SIGNATURE, detail, refTable.luaVersion)
                    .let { t -> if (t.luaVersion != refTable.luaVersion) t.copy(luaVersion = refTable.luaVersion,
                        missingRequired = LuaApiSymbols.requiredFor(refTable.luaVersion).filter { it !in t.functions }) else t }
                for ((n, r) in missReasons) if (n in table.missingRequired || n in table.missingCritical) log("特征码没找到 $n：$r")
                cacheKey?.let { saveDerived(it, table, missReasons) }
                Partial(table, SymbolAttempt(SymbolSource.SIGNATURE, if (table.usable) AttemptStatus.USED else AttemptStatus.FAILED,
                    table.describe() + missReasons.filterKeys { it in table.missingRequired }.entries.take(5)
                        .joinToString("") { "；${it.key}：${it.value}" }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("特征码搜索失败：${e.message}")
        }
    }

    /** 从参考 so 取每个函数从入口到结尾（最多 4 KB）的字节，按指令集通配。 */
    private fun buildSignatures(ref: ElfFile, refTable: LuaSymbolTable, names: List<String>): Map<CodeArch, List<FunctionSignature>> {
        // 符号大小：同一个地址取最大的 st_size（.symtab 和 .dynsym 可能都有）
        val sizes = HashMap<Long, Long>()
        ref.forEachSymbol(LuaApiSymbols::mayMatch) { sym ->
            if (sym.isFunction && sym.isDefined && sym.value != 0L) sizes.merge(sym.value, sym.size, ::maxOf)
        }
        val result = LinkedHashMap<CodeArch, MutableList<FunctionSignature>>()
        for (name in names) {
            val value = refTable.functions[name] ?: continue
            val arch = SignatureScanner.archOf(ref.machine, value) ?: continue
            val address = if (arch == CodeArch.THUMB) value and 1L.inv() else value
            val size = (sizes[value] ?: 0L).takeIf { it > 0 } ?: DEFAULT_UNKNOWN_SIZE
            val len = minOf(size, SignatureScanner.MAX_LENGTH.toLong()).toInt()
            val offset = ref.vaddrToOffset(address) ?: continue
            val bytes = try {
                ref.readBytes(offset, len)
            } catch (_: Exception) {
                continue
            }
            result.getOrPut(arch) { ArrayList() } += FunctionSignature(name, value, ArmCodeMasker.mask(bytes, arch, address))
        }
        return result
    }

    /** 参考 so 里紧挨着 [missing] 前后的函数（任何函数，含 static），只当推算位置的锚点。 */
    private fun neighbourSignatures(ref: ElfFile, missing: List<FunctionSignature>, exclude: Set<Long>, arch: CodeArch): List<FunctionSignature> {
        val funcs = ArrayList<Pair<Long, Long>>() // 地址 → 大小
        ref.forEachSymbol({ _, _, _ -> true }, listOf(".symtab")) { sym ->
            if (sym.isFunction && sym.isDefined && sym.value != 0L && sym.size > 0) funcs += sym.value to sym.size
        }
        val sorted = funcs.distinctBy { it.first }.sortedBy { it.first and 1L.inv() }
        val wanted = LinkedHashSet<Pair<Long, Long>>()
        for (m in missing) {
            val i = sorted.indexOfFirst { it.first == m.referenceValue }
            if (i < 0) continue
            for (j in listOf(i - 2, i - 1, i + 1, i + 2)) sorted.getOrNull(j)?.let { if (it.first !in exclude) wanted += it }
        }
        return wanted.mapNotNull { (value, size) ->
            if (SignatureScanner.archOf(ref.machine, value) != arch) return@mapNotNull null
            val address = if (arch == CodeArch.THUMB) value and 1L.inv() else value
            val offset = ref.vaddrToOffset(address) ?: return@mapNotNull null
            val bytes = try {
                ref.readBytes(offset, minOf(size, SignatureScanner.MAX_LENGTH.toLong()).toInt())
            } catch (_: Exception) {
                return@mapNotNull null
            }
            FunctionSignature("#neighbour@" + java.lang.Long.toHexString(value), value, ArmCodeMasker.mask(bytes, arch, address))
        }
    }

    // ---------------- 缓存：设备符号、特征码结果 ----------------

    private fun derivedFile(key: String) = cacheDir?.let { File(it, "$key.properties") }

    private fun loadDerived(key: String): LuaSymbolTable? {
        val f = derivedFile(key) ?: return null
        if (!f.isFile) return null
        return try {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            if (p.getProperty("format") != DERIVED_FORMAT) return null
            val found = HashMap<String, MutableSet<Long>>()
            for (k in p.stringPropertyNames()) {
                if (k.startsWith("fn.")) found[k.removePrefix("fn.")] = linkedSetOf(p.getProperty(k).removePrefix("0x").toLong(16))
            }
            val version = DetectedLuaVersion.entries.firstOrNull { it.name == p.getProperty("lua") } ?: DetectedLuaVersion.UNKNOWN
            val source = SymbolSource.entries.firstOrNull { it.name == p.getProperty("source") } ?: return null
            val t = LuaSymbolResolver.buildTable(found, p.getProperty("buildId") ?: return null, p.getProperty("abi")?.ifEmpty { null },
                p.getProperty("sourcePath").orEmpty(), source, p.getProperty("detail").orEmpty(), version)
            if (t.luaVersion != version) t.copy(luaVersion = version, missingRequired = LuaApiSymbols.requiredFor(version).filter { it !in t.functions }) else t
        } catch (_: Exception) {
            null
        }
    }

    private fun saveDerived(key: String, table: LuaSymbolTable, missReasons: Map<String, String>) {
        val f = derivedFile(key) ?: return
        try {
            f.parentFile?.mkdirs()
            val p = Properties()
            p["format"] = DERIVED_FORMAT
            p["buildId"] = table.buildId
            p["abi"] = table.abi ?: ""
            p["lua"] = table.luaVersion.name
            p["source"] = table.source.name
            p["sourcePath"] = table.sourcePath
            p["detail"] = table.sourceDetail
            table.functions.forEach { (k, v) -> p["fn.$k"] = "0x" + java.lang.Long.toHexString(v) }
            missReasons.forEach { (k, v) -> p["miss.$k"] = v }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { p.store(it, "EmmyLua Android attach derived symbols") }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
            // 缓存失败不影响本次附加
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("已取消")
    }

    companion object {
        private const val DERIVED_FORMAT = "1"
        /** 参考 so 的符号没有大小时按 256 字节取。 */
        private const val DEFAULT_UNKNOWN_SIZE = 256L
    }
}

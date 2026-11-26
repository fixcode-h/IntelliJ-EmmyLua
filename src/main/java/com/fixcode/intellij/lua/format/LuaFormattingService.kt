package com.fixcode.intellij.lua.format

import com.fixcode.intellij.lua.lang.LuaFileType
import com.fixcode.intellij.lua.lsp.EmmyLuaServerLocator
import com.fixcode.intellij.lua.project.LuaFormatterConfig
import com.fixcode.intellij.lua.project.LuaSettings
import com.intellij.application.options.CodeStyle
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 整个文件的格式化（Ctrl+Alt+L、保存时格式化）：先给表字段补对齐提示（[LuaAlignHints]），再交给插件自带的、
 * 和 emmylua_ls 同版本的 `luafmt` 排版。规则和 emmylua_ls 的格式化一样读 `.luafmt.toml`；项目里没有这个文件时
 * 用插件的默认配置（[LuaFormatterConfig.TEMPLATE]，接近旧版「代码样式 → Lua」的效果，含连续赋值对齐）。
 *
 * 为什么不直接让 emmylua_ls 格式化：它按自己同步到的文档内容排，IDE 没法在请求里先补提示；而 emmylua_formatter
 * 只在源码里已经有对齐迹象时才对齐表字段，旧版用户按 Ctrl+Alt+L 看起来就是「没反应」。
 *
 * 只接整个文件：选中一段格式化（不声明 FORMAT_FRAGMENTS）、设置里关掉、找不到 luafmt 时都交回 LSP4IJ，
 * 走 emmylua_ls 的 formatting / rangeFormatting。
 */
class LuaFormattingService : AsyncDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

    override fun canFormat(file: PsiFile): Boolean =
        file.fileType == LuaFileType && LuaSettings.instance.alignTableFields && LuaFormatterBinary.locate() != null

    override fun getName(): String = "EmmyLua"

    override fun getNotificationGroupId(): String = "EmmyLua Formatting"

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val exe = LuaFormatterBinary.locate() ?: return null
        val ioFile = request.ioFile ?: return null
        val text = request.documentText
        // 只认整个文件；万一 IDE 把一段交过来，宁可不格式化也不改选区外面
        val ranges = request.formattingRanges
        if (ranges.size != 1 || ranges[0].startOffset != 0 || ranges[0].endOffset != text.length) return null
        val psiFile = request.context.containingFile
        val indent = CodeStyle.getIndentOptions(psiFile)
        val args = buildList {
            add(exe.toString())
            add("--stdin")
            add("--stdin-filename"); add(ioFile.absolutePath)
            if (indent.USE_TAB_CHARACTER) add("--tab") else { add("--spaces"); add(indent.INDENT_SIZE.coerceAtLeast(1).toString()) }
            if (LuaFormatterConfig.findFor(ioFile.toPath()) == null) {
                LuaFormatterBinary.defaultConfig()?.let { add("--config"); add(it.toString()) }
            }
        }
        return Task(request, args, ioFile.parentFile, text)
    }

    private class Task(
        private val request: AsyncFormattingRequest,
        private val args: List<String>,
        private val workDir: File?,
        private val original: String,
    ) : FormattingTask {
        @Volatile private var process: Process? = null
        @Volatile private var cancelled = false

        override fun run() {
            val input = LuaAlignHints.apply(original)
            val result = runCatching { runFormatter(input) }
            if (cancelled) return
            result.onSuccess { out ->
                // 有语法错误时 luafmt 原样输出、退出码非 0：和 emmylua_ls 一样不改文件（补的提示也不能留下）
                request.onTextReady(out ?: original)
            }.onFailure { e ->
                LOG.warn("luafmt failed: $args", e)
                request.onError("EmmyLua 格式化失败", e.message ?: e.javaClass.name)
            }
        }

        private fun runFormatter(input: String): String? {
            val p = ProcessBuilder(args).directory(workDir?.takeIf { it.isDirectory }).redirectErrorStream(false).start()
            process = p
            // stdout / stderr 另开线程读，输入大时不互相卡住
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outReader = Thread { stdout.append(p.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)) }.apply { isDaemon = true; start() }
            val errReader = Thread { stderr.append(p.errorStream.readAllBytes().toString(StandardCharsets.UTF_8)) }.apply { isDaemon = true; start() }
            p.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                error("luafmt 超过 $TIMEOUT_SECONDS 秒没有返回")
            }
            outReader.join(1000)
            errReader.join(1000)
            if (p.exitValue() != 0) {
                LOG.info("luafmt exit ${p.exitValue()}: ${stderr.toString().take(500)}")
                return null
            }
            // 文档里的换行一律是 \n；luafmt 跟着输入走，这里再保险一次
            return stdout.toString().replace("\r\n", "\n")
        }

        override fun cancel(): Boolean {
            cancelled = true
            process?.destroyForcibly()
            return true
        }

        override fun isRunUnderProgress(): Boolean = true
    }

    companion object {
        private val LOG = logger<LuaFormattingService>()
        private const val TIMEOUT_SECONDS = 20L
    }
}

/** 插件自带的 luafmt（emmylua_formatter 的命令行，和 emmylua_ls 同一个 Release）。 */
object LuaFormatterBinary {
    private val LOG = logger<LuaFormatterBinary>()

    val executableName: String get() = if (SystemInfo.isWindows) "luafmt.exe" else "luafmt"

    /**
     * 设置里指定了 emmylua_ls 时先找它旁边的 luafmt（同一个包解出来的版本一致），再用插件自带的。
     * 不找 PATH：同名的 luafmt 还有别的 Lua 格式化工具，规则完全不同。
     */
    fun locate(): Path? {
        val located = EmmyLuaServerLocator.locate()
        val candidates = listOfNotNull(
            located?.path?.parent?.resolve(executableName),
            EmmyLuaServerLocator.bundled()?.parent?.resolve(executableName),
        )
        val exe = candidates.firstOrNull { Files.isRegularFile(it) } ?: return null
        if (!SystemInfo.isWindows && !Files.isExecutable(exe)) {
            runCatching { exe.toFile().setExecutable(true, false) }.onFailure { LOG.warn("chmod +x $exe failed", it) }
        }
        return exe
    }

    /** 项目里没有 `.luafmt.toml` 时用的配置：写在 IDE 的系统目录里，模板变了就重写。 */
    fun defaultConfig(): Path? = runCatching {
        val file = Path.of(PathManager.getSystemPath(), "emmylua", "luafmt-default.toml")
        val content = LuaFormatterConfig.TEMPLATE
        if (!Files.isRegularFile(file) || Files.readString(file) != content) {
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
        }
        file
    }.onFailure { LOG.warn("write default luafmt config failed", it) }.getOrNull()
}

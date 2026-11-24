package com.fixcode.intellij.lua.translation

/** 译文怎么显示（悬停文档、「翻译选中内容」弹窗共用）。 */
enum class TranslationDisplay(val label: String) {
    /** 每段原文下面跟着译文（默认）。 */
    BILINGUAL("双语对照"),

    /** 只显示译文；译文没到或失败时显示原文。 */
    TRANSLATION_ONLY("只显示译文");

    override fun toString(): String = label
}

/** 用哪个免费翻译服务。枚举名会写进缓存键，和 UEToVS 的写法一致（Google / Microsoft / MyMemory）。 */
enum class TranslationProviderKind(val cacheName: String, val label: String) {
    GOOGLE("Google", "谷歌翻译"),
    MICROSOFT("Microsoft", "微软翻译"),
    MY_MEMORY("MyMemory", "MyMemory");

    override fun toString(): String = label
}

/** 注释里的一段：一个段落或一个列表项（硬换行已合并成一行）。 */
data class TextUnit(
    val text: String,
    /** 代码行、已经是目标语言的内容为 false。 */
    val translate: Boolean,
    /** 原文里这一段前面有空行。 */
    val gapBefore: Boolean,
)

/**
 * 注释翻译的纯文本处理（照 UEToVS 的 `TranslationText`）：把注释切成段（硬换行的段落合并，列表项和 @标签单独成段，
 * 代码行不翻译），解析目标语言并换成各服务的写法，把太长的一段切成服务能收的块。不碰 I/O。
 */
object TranslationText {
    /** 一次最多翻译的字数（选中的内容超出部分截掉）。 */
    const val MAX_TOTAL_CHARS = 6000

    const val AUTO_TARGET = "auto"

    val TARGET_LANGUAGES = listOf("zh-CN", "zh-TW", "en", "ja", "ko")

    /** 「自动」（或空、不认识的）：中文界面翻译成简体中文，英文界面翻译成英文（只翻中文注释）。 */
    fun resolveTarget(setting: String?, englishUi: Boolean): String {
        val s = setting?.trim().orEmpty()
        TARGET_LANGUAGES.firstOrNull { it.equals(s, ignoreCase = true) }?.let { return it }
        return if (englishUi) "en" else "zh-CN"
    }

    /** 目标语言在各服务里的写法。 */
    fun providerCode(provider: TranslationProviderKind, target: String): String {
        if (provider == TranslationProviderKind.MICROSOFT) {
            if (target.equals("zh-CN", ignoreCase = true)) return "zh-Hans"
            if (target.equals("zh-TW", ignoreCase = true)) return "zh-Hant"
        }
        return target
    }

    /** 把（去掉注释符号后的）注释切成段。空行分段，硬换行的行合并。 */
    fun split(text: String?, target: String): List<TextUnit> {
        val units = ArrayList<TextUnit>()
        if (text.isNullOrBlank()) return units
        val src = if (text.length > MAX_TOTAL_CHARS) text.substring(0, MAX_TOTAL_CHARS) else text
        val current = StringBuilder()
        var currentCode = false
        var gap = false
        var pendingGap = false

        fun flush() {
            if (current.isEmpty()) return
            val s = current.toString().trim()
            current.setLength(0)
            if (s.isEmpty()) return
            units += TextUnit(s, !currentCode && needsTranslation(s, target), gap)
            gap = false
        }

        for (raw in src.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flush()
                pendingGap = units.isNotEmpty()
                continue
            }
            val code = looksLikeCode(trimmed) || line.startsWith("    ") || line.startsWith("\t")
            val startsItem = startsListItem(trimmed)
            if (current.isNotEmpty() && (startsItem || code || currentCode)) flush()
            if (current.isEmpty()) {
                if (pendingGap) gap = true
                pendingGap = false
                currentCode = code
            } else {
                current.append(if (code) '\n' else ' ')
            }
            current.append(if (code) line.trimStart() else trimmed)
        }
        flush()
        return units
    }

    /** `- 项`、`* 项`、`1. 项`、`@param X`、`@*param*`（emmylua_ls 悬停里的写法）开始新的一段。 */
    fun startsListItem(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        val c = trimmed[0]
        if ((c == '-' || c == '*' || c == '•' || c == '+') && trimmed.length > 1 && trimmed[1] == ' ') return true
        if (c == '@' || c == '\\') return trimmed.length > 1 && (trimmed[1].isLetter() || trimmed[1] == '*' || trimmed[1] == '_')
        var i = 0
        while (i < trimmed.length && trimmed[i].isDigit()) i++
        return i > 0 && i < trimmed.length - 1 && (trimmed[i] == '.' || trimmed[i] == ')') && trimmed[i + 1] == ' '
    }

    private val CODE_CHARS = charArrayOf('(', ')', '=', '<', '>', '[', ']', '{', '}')

    /** 注释里的一行代码（以 ; { } 结尾、Lua 的 `local x =` / `end` / `return`，或大半是符号）。 */
    fun looksLikeCode(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        val last = trimmed.last()
        if (last == '{' || last == '}') return true
        // "Foo(5);" 是代码，"… are trivially relocatable;"（折行的句子）不是
        if (last == ';' && (trimmed.indexOfAny(CODE_CHARS) >= 0 || trimmed.split(' ').size <= 3)) return true
        if (trimmed.startsWith("#include") || trimmed.startsWith("//")) return true
        if (LUA_CODE.matches(trimmed)) return true
        var letters = 0
        var symbols = 0
        for (ch in trimmed) {
            if (ch.isLetter()) letters++
            else if (!ch.isWhitespace() && !ch.isDigit() && ch != '.' && ch != ',' && ch != '\'' && ch != '"') symbols++
        }
        return trimmed.length >= 8 && symbols > letters
    }

    /** 注释里的 Lua 示例：`local x = …`、`x = f(…)`、`function a.b(…)`、`end`、`return x`、`f(…)`。 */
    private val LUA_CODE = Regex(
        """^(local\s+[\w,\s]+=.*|[\w.:\[\]"']+\s*=\s*.+[)}\]"'\d]|(local\s+)?function\s+[\w.:]+\s*\(.*|end[,)]?|return(\s+[\w.:()\[\]"',\s]*)?|[\w.:]+\(.*\))$"""
    )

    /** 值得发出去翻译：有词，而且不是已经用目标语言写的。 */
    fun needsTranslation(text: String?, target: String): Boolean {
        if (text.isNullOrBlank()) return false
        var latinWords = 0
        var cjk = 0
        var letters = 0
        var inWord = false
        for (c in text) {
            if (isCjk(c)) {
                cjk++
                letters++
                inWord = false
                continue
            }
            val latin = c in 'a'..'z' || c in 'A'..'Z'
            if (latin) {
                letters++
                if (!inWord) latinWords++
            }
            inWord = latin
        }
        if (target.equals("en", ignoreCase = true)) return cjk > 0
        if (letters == 0) return false
        if (cjk * 3 >= letters) return false // 已经（大半）是中日韩文
        return latinWords >= 2
    }

    fun isCjk(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' || c in '぀'..'ヿ' ||
            c in '가'..'힯' || c in '豈'..'﫿'

    private fun isCjkPunct(c: Char): Boolean = c in '　'..'〿' || c in '＀'..'￯'

    /** 把一段切成不超过 [max] 字的块，先在句末切，再在空格处切。 */
    fun chunk(text: String?, max: Int): List<String> {
        val list = ArrayList<String>()
        if (text.isNullOrEmpty()) return list
        if (max <= 0 || text.length <= max) return listOf(text)
        var start = 0
        while (start < text.length) {
            val remaining = text.length - start
            if (remaining <= max) {
                list += text.substring(start)
                break
            }
            var cut = -1
            var i = start + max - 1
            while (i > start + max / 3) {
                val c = text[i]
                if ((c == '.' || c == '!' || c == '?' || c == ';' || c == '。') && (i + 1 >= text.length || text[i + 1] == ' ' || text[i + 1] == '\n')) {
                    cut = i + 1
                    break
                }
                i--
            }
            if (cut < 0) {
                i = start + max - 1
                while (i > start) {
                    if (text[i] == ' ') {
                        cut = i
                        break
                    }
                    i--
                }
            }
            if (cut <= start) cut = start + max
            list += text.substring(start, cut)
            start = cut
            while (start < text.length && text[start] == ' ') start++
        }
        return list
    }

    /** 拼接各块的译文：中日韩文之间不加空格，其他加一个空格。 */
    fun joinChunks(chunks: List<String?>): String {
        val sb = StringBuilder()
        for (c in chunks) {
            val t = c?.trim().orEmpty()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty() && !(isCjk(sb.last()) || isCjkPunct(sb.last())) && !isCjk(t[0])) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    /**
     * 去掉注释符号，留下要翻译的文字（「翻译选中内容」用）：Lua 的 `--` / `---` / `--[[ ]]`，
     * 以及别的语言常见的 `//`、`/* */`、`*`、`#`。`---@param` 变成 `@param`，仍单独成段。
     */
    fun cleanComment(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val out = ArrayList<String>()
        for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            // 行首缩进去掉：注释符号前的缩进对正文没有意义（不然每行都被当成缩进的代码）；符号后面的缩进保留
            var t = line.trimEnd().trimStart()
            // 块注释的开头和结尾：--[[ / --[==[ / ]] / ]==] / /* / */
            val stripped = t.replace(BLOCK_OPEN, "").replace(BLOCK_CLOSE, "")
            if (stripped != t) t = stripped.trim()
            t = when {
                t.startsWith("---") -> t.removePrefix("---").let { if (it.startsWith(' ')) it.substring(1) else it }
                t.startsWith("--") -> t.removePrefix("--").let { if (it.startsWith(' ')) it.substring(1) else it }
                t.startsWith("///") -> t.removePrefix("///").trimStart()
                t.startsWith("//") -> t.removePrefix("//").trimStart()
                t.startsWith("* ") || t == "*" -> t.removePrefix("*").trimStart()
                t.startsWith("# ") || t == "#" -> t.removePrefix("#").trimStart()
                else -> t
            }
            out += t
        }
        val text = out.joinToString("\n").trim()
        return text.ifBlank { null }
    }

    private val BLOCK_OPEN = Regex("""^(--\[=*\[|/\*+)""")
    private val BLOCK_CLOSE = Regex("""(\]=*\]|\*+/)\s*$""")
}

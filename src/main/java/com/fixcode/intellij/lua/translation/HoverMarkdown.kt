package com.fixcode.intellij.lua.translation

/**
 * emmylua_ls 悬停文档（Markdown）的切段和拼回：代码块、分隔线、表格、缩进代码原样保留，其余按段落 / 列表项 /
 * `@param` 切成 [TextUnit]，翻译后把译文插回原文后面（双语）或替换原文（只显示译文）。
 *
 * 译文不直接写进 Markdown（译文里的 `*`、`_` 会被当成格式），而是先放一个占位段落 [slot]，Markdown 转成 HTML 后
 * 再把占位换成 [translationHtml]。纯函数，不碰 IntelliJ。
 */
class HoverMarkdown private constructor(val pieces: List<Piece>) {
    sealed interface Piece {
        val lines: List<String>
    }

    /** 原样保留的行（代码块、分隔线、空行……）。 */
    data class Raw(override val lines: List<String>) : Piece

    /** 一段要看要不要翻译的文字：原来的 Markdown 行 + 合并后的文本。 */
    data class Para(override val lines: List<String>, val unit: TextUnit) : Piece

    /** 需要翻译的段。 */
    val units: List<TextUnit> get() = pieces.mapNotNull { (it as? Para)?.unit?.takeIf { u -> u.translate } }

    val hasTranslatable: Boolean get() = pieces.any { it is Para && it.unit.translate }

    /**
     * 拼回 Markdown。[translation] 给出某段的译文（还没到返回 null）；有译文的段在后面（双语）或原位（只显示译文）放一个占位，
     * 返回拼好的 Markdown 和每个占位对应的译文（按占位编号）。
     */
    fun render(display: TranslationDisplay, translation: (TextUnit) -> String?): Rendered {
        val out = ArrayList<String>()
        val slots = ArrayList<String>()
        for (p in pieces) {
            val t = if (p is Para && p.unit.translate) translation(p.unit) else null
            if (t == null) {
                out += p.lines
                continue
            }
            if (display == TranslationDisplay.BILINGUAL) out += p.lines
            // 占位前后空一行：自成一个段落，转换后是 <p>占位</p>
            out += ""
            out += slot(slots.size)
            out += ""
            slots += t
        }
        return Rendered(out.joinToString("\n"), slots)
    }

    data class Rendered(val markdown: String, val slots: List<String>)

    companion object {
        private const val SLOT_PREFIX = "ZZEMMYLUATRSLOT"
        private val SLOT_HTML = Regex("""(?:<p>\s*)?$SLOT_PREFIX(\d+)ZZ(?:\s*</p>)?""")
        private val FENCE = Regex("""^\s{0,3}(```|~~~)""")
        private val HR = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")

        fun slot(i: Int): String = "${SLOT_PREFIX}${i}ZZ"

        fun parse(markdown: String?, target: String): HoverMarkdown {
            val pieces = ArrayList<Piece>()
            if (markdown.isNullOrEmpty()) return HoverMarkdown(pieces)
            val para = ArrayList<String>()
            var fence: String? = null

            fun flush() {
                if (para.isEmpty()) return
                val text = para.joinToString(" ") { it.trim() }.trim()
                pieces += Para(ArrayList(para), TextUnit(text, TranslationText.needsTranslation(text, target), false))
                para.clear()
            }

            for (line in markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val trimmed = line.trim()
                if (fence != null) {
                    pieces += Raw(listOf(line))
                    if (trimmed.startsWith(fence)) fence = null
                    continue
                }
                val f = FENCE.find(line)
                if (f != null) {
                    flush()
                    fence = f.groupValues[1]
                    pieces += Raw(listOf(line))
                    continue
                }
                val raw = trimmed.isEmpty() || HR.matches(line) || trimmed.startsWith("|") || trimmed.startsWith("<") ||
                    (para.isEmpty() && (line.startsWith("    ") || line.startsWith("\t"))) ||
                    TranslationText.looksLikeCode(trimmed)
                if (raw) {
                    flush()
                    pieces += Raw(listOf(line))
                    continue
                }
                if (para.isNotEmpty() && (TranslationText.startsListItem(trimmed) || trimmed.startsWith("#"))) flush()
                para += line
                // 标题自成一段
                if (trimmed.startsWith("#")) flush()
            }
            flush()
            return HoverMarkdown(pieces)
        }

        /** 把转换后 HTML 里的占位换成译文。 */
        fun fillSlots(html: String, slots: List<String>, slotHtml: (String) -> String = ::translationHtml): String =
            SLOT_HTML.replace(html) { m ->
                val i = m.groupValues[1].toIntOrNull()
                if (i != null && i in slots.indices) slotHtml(slots[i]) else ""
            }

        /** 一段译文的 HTML：左边一条细的强调色竖线（和 UEToVS 一样），行内代码保留。 */
        fun translationHtml(text: String): String =
            "<div style=\"border-left: 2px solid #D97757; padding-left: 8px; margin-top: 2px; margin-bottom: 4px;\">" +
                inlineHtml(text) + "</div>"

        /** 转义译文，`代码` 变成 <code>，**粗体** 变成 <b>。 */
        fun inlineHtml(text: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '`') {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        sb.append("<code>").append(escape(text.substring(i + 1, end))).append("</code>")
                        i = end + 1
                        continue
                    }
                }
                if (c == '*' && text.startsWith("**", i)) {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        sb.append("<b>").append(escape(text.substring(i + 2, end))).append("</b>")
                        i = end + 2
                        continue
                    }
                }
                sb.append(escape(c.toString()))
                i++
            }
            return sb.toString()
        }

        fun escape(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }

        /** 文档最后一行的状态：翻译中 / 翻译失败。没有要说的返回空串。 */
        fun statusHtml(pending: Int, firstError: String?): String = when {
            pending > 0 -> "<p style=\"color: gray;\"><small>翻译中…</small></p>"
            firstError != null -> "<p style=\"color: gray;\"><small>翻译失败：${escape(firstError)}</small></p>"
            else -> ""
        }
    }
}

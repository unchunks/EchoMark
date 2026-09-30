package com.unchunks.echomark.ui.chat

/**
 * チャットの回答を表示するための軽量な Markdown パーサ(Android 依存なし)。
 * AI の回答でよく使われる範囲だけを扱う:
 * - ブロック: 見出し `#`〜`###`、箇条書き `- ` / `* ` / `• `、番号付き `1. `、コードブロック ```、区切り線 `---`、段落
 * - インライン: 太字 `**`、斜体 `*`、インラインコード `` ` ``、引用番号 `[1]` / `[1, 2]`、リンク `[文字](URL)`(文字だけ表示)
 *
 * 生成途中(ストリーミング)の不完全な記法は、閉じられるまで文字のまま表示する。
 */
object ChatMarkdown {

    sealed interface Block {
        data class Heading(val level: Int, val spans: List<Span>) : Block
        data class Paragraph(val spans: List<Span>) : Block

        /** 箇条書きの1項目。[marker] は表示する記号("•" や "1.")、[indent] は入れ子の深さ(0 始まり)。 */
        data class ListItem(val marker: String, val ordered: Boolean, val indent: Int, val spans: List<Span>) : Block
        data class Code(val language: String?, val code: String) : Block
        data object Divider : Block
    }

    /** 同じ装飾が続く文字列の断片。[citation] が非 null なら引用番号(表示は "[n]")。 */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val citation: Int? = null
    )

    private const val TAB_WIDTH = 4

    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^(\\s*)[-*•・]\\s+(.*)$")
    private val orderedRegex = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val dividerRegex = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val fenceRegex = Regex("^\\s*```\\s*([\\w+#.-]*)\\s*$")
    private val citationRegex = Regex("^\\[(\\d{1,3}(?:\\s*[,、]\\s*\\d{1,3})*)]")
    private val linkRegex = Regex("^\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)")

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        val lines = markdown.replace("\r\n", "\n").split("\n")

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(parseInline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = fenceRegex.matchEntire(line)
            if (fence != null) {
                flushParagraph()
                // 閉じていなければ(生成途中)最後までをコードとして扱う
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && fenceRegex.matchEntire(lines[i]) == null) {
                    codeLines += lines[i]
                    i++
                }
                blocks += Block.Code(fence.groupValues[1].ifEmpty { null }, codeLines.joinToString("\n"))
                i++ // 閉じの ```
                continue
            }

            val heading = headingRegex.matchEntire(line)
            val ordered = orderedRegex.matchEntire(line)
            val bullet = bulletRegex.matchEntire(line)
            when {
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    blocks += Block.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2].trim()))
                }
                dividerRegex.matches(line) -> {
                    flushParagraph()
                    blocks += Block.Divider
                }
                ordered != null -> {
                    flushParagraph()
                    blocks += Block.ListItem(
                        marker = "${ordered.groupValues[2]}.",
                        ordered = true,
                        indent = indentLevel(ordered.groupValues[1]),
                        spans = parseInline(ordered.groupValues[3])
                    )
                }
                bullet != null -> {
                    flushParagraph()
                    blocks += Block.ListItem(
                        marker = "•",
                        ordered = false,
                        indent = indentLevel(bullet.groupValues[1]),
                        spans = parseInline(bullet.groupValues[2])
                    )
                }
                else -> paragraph += line.trimEnd()
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    /** 2 スペース(またはタブ)ごとに1段。深すぎる入れ子は 2 段までにまとめる。 */
    private fun indentLevel(leading: String): Int {
        val width = leading.count { it != '\t' } + TAB_WIDTH * leading.count { it == '\t' }
        return (width / 2).coerceAtMost(2)
    }

    fun parseInline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false

        fun flush() {
            if (buffer.isNotEmpty()) {
                spans += Span(buffer.toString(), bold = bold, italic = italic)
                buffer.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        spans += Span(text.substring(i + 1, end), bold = bold, italic = italic, code = true)
                        i = end + 1
                    } else {
                        buffer.append(c)
                        i++
                    }
                }
                text.startsWith("**", i) -> {
                    // 開きは対応する閉じがあるときだけ装飾にする(生成途中の "**" はそのまま出す)
                    if (bold || text.indexOf("**", i + 2) > i + 2) {
                        flush()
                        bold = !bold
                    } else {
                        buffer.append("**")
                    }
                    i += 2
                }
                c == '*' -> {
                    val next = text.getOrNull(i + 1)
                    val opens = !italic && next != null && !next.isWhitespace() && text.indexOf('*', i + 1) > i + 1
                    if (italic || opens) {
                        flush()
                        italic = !italic
                    } else {
                        buffer.append(c)
                    }
                    i++
                }
                c == '[' -> {
                    val rest = text.substring(i)
                    val citation = citationRegex.find(rest)
                    val link = if (citation == null) linkRegex.find(rest) else null
                    when {
                        citation != null -> {
                            flush()
                            citation.groupValues[1].split(',', '、')
                                .mapNotNull { it.trim().toIntOrNull() }
                                .forEach { spans += Span("[$it]", bold = bold, italic = italic, citation = it) }
                            i += citation.value.length
                        }
                        link != null -> {
                            buffer.append(link.groupValues[1])
                            i += link.value.length
                        }
                        else -> {
                            buffer.append(c)
                            i++
                        }
                    }
                }
                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
        return mergeAdjacent(spans)
    }

    /** 同じ装飾の断片をまとめる(描画の手間を減らし、テストも読みやすくする)。 */
    private fun mergeAdjacent(spans: List<Span>): List<Span> {
        val merged = mutableListOf<Span>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.citation == null && span.citation == null &&
                last.bold == span.bold && last.italic == span.italic && last.code == span.code && !span.code
            ) {
                merged[merged.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                merged += span
            }
        }
        return merged
    }

    /** コピー用などに、記法を取り除いた文字列にする。 */
    fun toPlainText(markdown: String): String = parse(markdown).joinToString("\n") { block ->
        when (block) {
            is Block.Heading -> block.spans.joinToString("") { it.text }
            is Block.Paragraph -> block.spans.joinToString("") { it.text }
            is Block.ListItem -> "  ".repeat(block.indent) + block.marker + " " + block.spans.joinToString("") { it.text }
            is Block.Code -> block.code
            Block.Divider -> "―――"
        }
    }
}

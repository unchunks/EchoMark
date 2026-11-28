package com.unchunks.echomark.domain.bookmark.model

import com.unchunks.echomark.domain.extract.ExtractedBody

/** 読書モード(#59)の文字の大きさ。[sp] は本文の大きさ。 */
enum class ReadingFontSize(val sp: Int) {
    SMALL(15),
    MEDIUM(17),
    LARGE(20),
    XLARGE(24);

    /** 1段階大きく(いちばん大きければそのまま) */
    fun larger(): ReadingFontSize = entries.getOrElse(ordinal + 1) { this }

    /** 1段階小さく(いちばん小さければそのまま) */
    fun smaller(): ReadingFontSize = entries.getOrElse(ordinal - 1) { this }
}

/** 読書モードの背景と文字の色。[SYSTEM] は端末の明るい・暗いに合わせる。 */
enum class ReadingTheme { SYSTEM, PAPER, WHITE, DARK }

/** 読書モードの書体。 */
enum class ReadingFont { SANS, SERIF }

/** 読書モードの見た目。端末に保存し、どのブックマークでも同じ設定で開く。 */
data class ReadingStyle(
    val fontSize: ReadingFontSize = ReadingFontSize.MEDIUM,
    val theme: ReadingTheme = ReadingTheme.SYSTEM,
    val font: ReadingFont = ReadingFont.SANS
)

/** 読書モードで見せる本文の区切り。 */
sealed interface ReadingBlock {
    /** 見出し(メモ・取り出した文字の種類など) */
    data class Heading(val text: String) : ReadingBlock

    /** 1つの段落 */
    data class Paragraph(val text: String) : ReadingBlock
}

/** 本文を、読書モードで並べる見出しと段落に分ける。純粋関数(JVM 単体テスト可能)。 */
object ReadingBlocks {

    /**
     * [content] を段落に分ける。ファイルから取り出した文字(画像・音声などの内容)が含まれるときは、メモと取り出した区画に見出しを付ける。
     * 空の行は段落の区切りで、空の行以外の行はそれぞれ1つの段落(取り出した文字の途中の改行も、段落の切れ目として読める)。
     * 本文が無ければ空。
     */
    fun from(content: String?): List<ReadingBlock> {
        if (content.isNullOrBlank()) return emptyList()
        val parts = ExtractedBody.parse(content)
        if (parts.sections.isEmpty()) return paragraphs(content)
        return buildList {
            parts.memo?.let {
                add(ReadingBlock.Heading("メモ"))
                addAll(paragraphs(it))
            }
            parts.sections.forEach { section ->
                add(ReadingBlock.Heading(section.source.label))
                addAll(paragraphs(section.text))
            }
        }
    }

    private fun paragraphs(text: String): List<ReadingBlock> =
        text.replace("\r\n", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }.map { ReadingBlock.Paragraph(it) }

    /** 読み進めた割合(0〜1)。[firstVisible] は画面の先頭に見えている区切りの番号、[total] は区切りの数。 */
    fun progress(firstVisible: Int, total: Int): Float =
        if (total <= 1) 0f else (firstVisible.toFloat() / (total - 1)).coerceIn(0f, 1f)
}

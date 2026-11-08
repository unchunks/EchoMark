package com.unchunks.echomark.domain.extract

/**
 * ブックマークの本文(content)に、ファイルから取り出したテキストを書き込む形式。
 *
 * ```
 * (ユーザーのメモ。無ければこの部分と続く空行は無い)
 *
 * --- 画像の内容 ---
 * (取り出したテキスト)
 * ```
 *
 * - 区切りは行全体が `--- 見出し ---` の行。見出しは [ExtractionSource.label] のどれか(それ以外の行はメモの一部とみなす)
 * - 最初の区切りより前がユーザーのメモ。取り出し直すときはメモを残し、区切り以降を新しい結果で置き換える(二重に追記しない)
 * - 詳細画面は [parse] でメモと取り出した部分に分けて、見出しを付けて表示できる
 */
object ExtractedBody {

    /** 長すぎて一部だけを入れたときに、取り出したテキストの末尾に添える注記 */
    const val TRUNCATED_NOTE = "(長いため先頭の一部のみ)"

    /** 本文の1区画(取り出したテキスト)。 */
    data class Section(val source: ExtractionSource, val text: String)

    /** 本文をメモと取り出した区画に分けたもの。 */
    data class Parts(val memo: String?, val sections: List<Section>)

    /** 区切りの行(例: `--- 文字起こし ---`)。 */
    fun separator(source: ExtractionSource): String = "--- ${source.label} ---"

    /** 本文をメモと取り出した区画に分ける。区切りが無ければ全体がメモ。 */
    fun parse(content: String?): Parts {
        if (content.isNullOrBlank()) return Parts(memo = null, sections = emptyList())
        val memoLines = mutableListOf<String>()
        val sections = mutableListOf<Section>()
        var current: ExtractionSource? = null
        val currentLines = mutableListOf<String>()
        fun flush() {
            current?.let { sections += Section(it, currentLines.joinToString("\n").trim()) }
            currentLines.clear()
        }
        for (line in content.replace("\r\n", "\n").lines()) {
            val source = sourceOfSeparator(line)
            when {
                source != null -> {
                    flush()
                    current = source
                }
                current == null -> memoLines += line
                else -> currentLines += line
            }
        }
        flush()
        val memo = memoLines.joinToString("\n").trim().takeIf { it.isNotEmpty() }
        return Parts(memo, sections)
    }

    /** ユーザーのメモ(最初の区切りより前)。無ければ null。 */
    fun memoOf(content: String?): String? = parse(content).memo

    /**
     * 既存の本文 [existing] のメモを残し、取り出した部分を [extracted] で置き換えた本文を返す。
     * 前回取り出した区画(見出しが違っても)は消す。
     */
    fun merge(existing: String?, extracted: ExtractedContent): String =
        compose(memoOf(existing), extracted)

    /** メモと取り出したテキストから本文を組み立てる。 */
    fun compose(memo: String?, extracted: ExtractedContent): String = buildString {
        if (!memo.isNullOrBlank()) {
            append(memo.trim())
            append("\n\n")
        }
        append(separator(extracted.source))
        append('\n')
        append(extracted.text.trim())
        if (extracted.truncated) {
            append("\n\n")
            append(TRUNCATED_NOTE)
        }
    }

    private fun sourceOfSeparator(line: String): ExtractionSource? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("--- ") || !trimmed.endsWith(" ---")) return null
        return ExtractionSource.ofLabel(trimmed.removePrefix("--- ").removeSuffix(" ---"))
    }
}

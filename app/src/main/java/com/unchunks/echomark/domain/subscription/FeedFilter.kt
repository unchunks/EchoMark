package com.unchunks.echomark.domain.subscription

import java.text.Normalizer
import java.util.Locale

/**
 * 購読の新着の絞り込み。記事・動画・論文のタイトルで決める(フィードには本文の無いものが多いため)。
 *
 * - [exclude] のどれかをタイトルに含むものは保存しない
 * - [include] が空でなければ、そのどれかをタイトルに含むものだけ保存する
 * - 全角・半角・大文字・小文字の違いは見ない(「ＡＩ」と「ai」は同じ)
 *
 * 絞り込みで保存しなかったものは「見た」ことにする(絞り込みを変えても、さかのぼって保存はしない)。
 */
data class FeedFilter(val include: List<String> = emptyList(), val exclude: List<String> = emptyList()) {

    val isEmpty: Boolean get() = include.isEmpty() && exclude.isEmpty()

    /** タイトルが [title] のものを保存するか。タイトルが無いものは、含む語を決めていなければ保存する */
    fun accepts(title: String?): Boolean {
        if (isEmpty) return true
        val text = normalize(title.orEmpty())
        if (exclude.any { text.contains(normalize(it)) }) return false
        return include.isEmpty() || include.any { text.contains(normalize(it)) }
    }

    companion object {
        /** 1つの欄に入れられる語の数の上限 */
        const val MAX_WORDS = 20

        private const val MAX_WORD_CHARS = 50

        /** `"語句"` か、空白・読点・カンマで区切った語 */
        private val TOKEN = Regex("\"([^\"]*)\"|[^\\s\"、,，]+")

        /** 入力(空白・読点・カンマ区切り。`"..."` は空白を含む語句)を語の一覧にする。同じ語は一度だけ */
        fun parse(input: String): List<String> =
            TOKEN.findAll(input)
                .map { (it.groups[1]?.value ?: it.value).replace(Regex("\\s+"), " ").trim().take(MAX_WORD_CHARS).trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { normalize(it) }
                .take(MAX_WORDS)
                .toList()

        /** DB に入れる形(改行で区切る) */
        fun encode(words: List<String>): String = words.joinToString("\n")

        /** DB の形(改行で区切る)から語の一覧にする */
        fun decode(stored: String): List<String> = stored.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        /** 入力欄に戻す形(空白を含む語句は `"..."` で囲む) */
        fun format(words: List<String>): String = words.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }

        private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }
}

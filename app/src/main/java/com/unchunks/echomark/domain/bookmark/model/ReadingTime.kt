package com.unchunks.echomark.domain.bookmark.model

import kotlin.math.ceil

/**
 * 本文を読むのにかかる時間の目安(#61)。純粋関数(JVM 単体テスト可能)。
 * 日本語(漢字・かな)は 1 分に [CJK_CHARS_PER_MINUTE] 文字、英語などは 1 分に [WORDS_PER_MINUTE] 語として、
 * 混ざった文章は両方を足す。
 */
object ReadingTime {
    const val CJK_CHARS_PER_MINUTE = 500
    const val WORDS_PER_MINUTE = 230

    /** これより短い文章は、目安を出さない(「約1分」と出しても役に立たない) */
    const val MIN_UNITS = 150

    /**
     * [text] を読むのにかかる分数(切り上げ、最短 1 分)。本文が無い・短いときは null。
     * 「短い」は、日本語の文字数と英語の語数の合計(語数は日本語の文字数に近い長さになるよう 2 倍して数える)が [MIN_UNITS] 未満のとき。
     */
    fun minutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        var cjk = 0
        var words = 0
        var inWord = false
        for (char in text) {
            when {
                EvidenceFinder.isCjk(char) -> {
                    cjk++
                    inWord = false
                }
                char.isLetterOrDigit() -> {
                    if (!inWord) words++
                    inWord = true
                }
                else -> inWord = false
            }
        }
        if (cjk + words * 2 < MIN_UNITS) return null
        val minutes = cjk.toDouble() / CJK_CHARS_PER_MINUTE + words.toDouble() / WORDS_PER_MINUTE
        return ceil(minutes).toInt().coerceAtLeast(1)
    }

    /** 「約5分」のような短い言い方。 */
    fun label(minutes: Int): String = "約${minutes}分"
}

/** [Bookmark] の本文を読むのにかかる分数の目安(本文が無い・短いときは null)。 */
fun Bookmark.readingMinutes(): Int? = ReadingTime.minutes(content)

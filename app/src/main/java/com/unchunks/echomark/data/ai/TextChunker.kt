package com.unchunks.echomark.data.ai

import kotlin.math.ceil
import kotlin.math.roundToInt

/** 長い本文の一部。[number] は 1 から数える。 */
data class TextPart(val number: Int, val total: Int, val text: String)

/** 長い本文を、AI に1回で渡せる長さの部分に分ける。 */
object TextChunker {

    /**
     * [text] を [maxChars] 文字以下の部分に分ける。段落 → 行 → 文 → 空白の境目を優先して切る。
     * 部分の長さがそろうよう、必要な個数で割った長さ(少し余裕を持たせる)を目安にする
     * (20,001 文字を「20,000 + 1」ではなく「約 10,000 × 2」に分ける)。
     * 空白だけの部分は返さない。
     */
    fun split(text: String, maxChars: Int): List<String> {
        require(maxChars > 0)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(trimmed)
        val count = ceil(trimmed.length / maxChars.toDouble()).toInt()
        val target = minOf(maxChars, ceil(trimmed.length / count.toDouble() * BALANCE_SLACK).toInt())
        val parts = mutableListOf<String>()
        var rest = trimmed
        while (rest.length > target) {
            val cut = cutPosition(rest, target)
            rest.substring(0, cut).trim().takeIf { it.isNotEmpty() }?.let { parts += it }
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotBlank()) parts += rest.trim()
        return parts
    }

    /**
     * 先頭 [limit] 文字の中で切る位置(切った後ろの最初の文字の位置)。
     * 前半で切ると部分が短くなりすぎるため、後半にある境目だけを使う。見つからなければ [limit] で切る。
     */
    private fun cutPosition(text: String, limit: Int): Int {
        val window = text.substring(0, limit)
        val minCut = limit / 2
        window.lastIndexOf("\n\n").takeIf { it >= minCut }?.let { return it + 2 }
        window.lastIndexOf('\n').takeIf { it >= minCut }?.let { return it + 1 }
        sentenceEnd(window)?.takeIf { it >= minCut }?.let { return it }
        window.indexOfLast { it.isWhitespace() }.takeIf { it >= minCut }?.let { return it + 1 }
        return limit
    }

    /** 最後の文末(句点・感嘆符・疑問符の直後)の位置。英文のピリオドは後ろが空白のときだけ文末とみなす(小数点などで切らない)。 */
    private fun sentenceEnd(window: String): Int? {
        for (i in window.lastIndex downTo 0) {
            val c = window[i]
            if (c in SENTENCE_ENDS) return i + 1
            if ((c == '.' || c == '!' || c == '?') && i + 1 < window.length && window[i + 1].isWhitespace()) return i + 1
        }
        return null
    }

    /**
     * [total] 個の部分から、最大 [max] 個を均等に選んだ位置(0 始まり・昇順)。先頭と末尾は必ず含める
     * (記事の導入と結論、動画の冒頭と締めを落とさない)。
     */
    fun selectEvenly(total: Int, max: Int): List<Int> {
        require(max > 0)
        if (total <= max) return (0 until total).toList()
        if (max == 1) return listOf(0)
        return (0 until max).map { i -> (i * (total - 1) / (max - 1).toDouble()).roundToInt() }.distinct()
    }

    private const val SENTENCE_ENDS = "。！？"

    /** 部分の長さをそろえるときの余裕(境目で切ると目安より短くなるため) */
    private const val BALANCE_SLACK = 1.1
}

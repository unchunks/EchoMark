package com.unchunks.echomark.domain.bookmark.model

import java.text.Normalizer

/**
 * 要約の1文と、その根拠になる本文の一節(#18)。
 * @property sentence 要約の1文(箇条書きの行頭の「・」は除いたもの)
 * @property quote 根拠にした本文の一節。見つからなければ null
 * @property support 要約の文の文字のつながり(2 文字ずつ)のうち、[quote] にも出てくる割合(0〜1)
 */
data class SentenceEvidence(val sentence: String, val quote: String?, val support: Float) {
    /** 本文に根拠が見つかったか */
    val isSupported: Boolean get() = quote != null
}

/**
 * 要約と本文の突き合わせの結果。
 * @property comparable 突き合わせられたか。要約と本文の言語が違うとき(英語の要約と日本語の本文など)や、本文が無いときは false
 */
data class EvidenceReport(val comparable: Boolean, val items: List<SentenceEvidence>) {
    /** 本文に根拠が見つからなかった文の数 */
    val unsupportedCount: Int get() = items.count { !it.isSupported }
}

/**
 * 要約の各文について、根拠になりそうな本文の一節を探す(#18)。純粋関数(JVM 単体テスト可能)。
 *
 * AI に「根拠の引用」を書かせると、本文にない引用を作ってしまう。ここでは要約の文と本文の文を文字のつながりで突き合わせ、
 * 本文にある一節だけを根拠として出す(本文に無い一節は出せない)。見つからない文は、その旨を出して確かめてもらう。
 * 言い換えが大きい要約では見つからないことがあるため、「根拠なし」は間違いの断定ではなく、確かめる目印として扱う。
 */
object EvidenceFinder {
    /** この割合以上の文字のつながりが本文の一節にもあれば、根拠が見つかったとする */
    const val SUPPORT_THRESHOLD = 0.4f

    /** 根拠として出す一節の最大の文字数(長ければ切る) */
    const val MAX_QUOTE_CHARS = 140

    fun find(summary: String, source: String): EvidenceReport {
        val sentences = splitSentences(summary).map { it.trimStart(*BULLETS).trim() }.filter { matchKey(it).length >= MIN_KEY_CHARS }
        val sourceSentences = splitSentences(source)
        if (sentences.isEmpty() || sourceSentences.isEmpty() || isCjkText(summary) != isCjkText(source)) {
            return EvidenceReport(comparable = false, items = emptyList())
        }
        val sourceBigrams = sourceSentences.map { bigrams(matchKey(it)) }
        val items = sentences.map { sentence ->
            val target = bigrams(matchKey(sentence))
            var best = 0f
            var bestWindow = IntRange.EMPTY
            // 1 文、または続く 2 文を1つの一節として比べる(要約は2文をまとめて書くことがある)
            for (start in sourceSentences.indices) {
                for (size in 1..MAX_WINDOW_SENTENCES) {
                    val end = start + size - 1
                    if (end >= sourceSentences.size) break
                    val window = HashSet<String>()
                    for (index in start..end) window += sourceBigrams[index]
                    val score = containment(target, window)
                    if (score > best) {
                        best = score
                        bestWindow = start..end
                    }
                }
            }
            val quote = if (best >= SUPPORT_THRESHOLD) shorten(bestWindow.joinToString(" ") { sourceSentences[it] }) else null
            SentenceEvidence(sentence, quote, best)
        }
        return EvidenceReport(comparable = true, items = items)
    }

    /** 文に分ける(句点・感嘆符・疑問符の後、改行、英語のピリオドの後)。空の文は除く。 */
    internal fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

    /** 比べるための形(全角・半角をそろえ、小文字にして、文字と数字だけを残す) */
    internal fun matchKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }

    private fun bigrams(key: String): Set<String> =
        if (key.length < 2) setOf(key) else key.windowed(2).toSet()

    private fun containment(target: Set<String>, window: Set<String>): Float =
        if (target.isEmpty()) 0f else target.count { it in window }.toFloat() / target.size

    private fun shorten(text: String): String =
        if (text.length <= MAX_QUOTE_CHARS) text else text.take(MAX_QUOTE_CHARS - 1) + "…"

    /** 日本語・中国語・韓国語の文字が、文字(英数字を含む)の 3 割以上か。言語が同じ系統かの目安に使う。 */
    internal fun isCjkText(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { isCjk(it) } * 100 >= letters.length * CJK_PERCENT
    }

    internal fun isCjk(char: Char): Boolean = when (Character.UnicodeScript.of(char.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL -> true
        else -> false
    }

    private const val MIN_KEY_CHARS = 4
    private const val MAX_WINDOW_SENTENCES = 2
    private const val CJK_PERCENT = 30
    private val BULLETS = charArrayOf('・', '•', '●', '-', '*', ' ', '　')
    private val SENTENCE_BREAK = Regex("""(?<=[。！？!?])\s*|\n+|(?<=\.)\s+""")
}

/** 要約の出来の問題(#25)。[hint] は、作り直すときに AI へ伝える直し方。 */
enum class SummaryProblem(val hint: String) {
    EMPTY("要約が空か短すぎました。本文の要点を書いてください。"),
    TOO_LONG("要約が長すぎました。指定の長さに収めてください。"),
    WRONG_LANGUAGE("指定した言語で書かれていませんでした。指定した言語だけで書いてください。"),
    COPIED("本文をそのまま写していました。短く言い換えてまとめてください。"),
    REPEATED("同じ言葉の繰り返しがありました。要点を1度ずつ書いてください。"),
    REFUSED("要約を断る文になっていました。本文の内容を要約してください。"),
    UNSUPPORTED("本文に書かれていないことが含まれていました。本文に書かれていることだけで書いてください。")
}

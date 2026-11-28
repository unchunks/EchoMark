package com.unchunks.echomark.domain.model

import com.unchunks.echomark.domain.bookmark.model.EvidenceFinder
import com.unchunks.echomark.domain.bookmark.model.SummaryProblem

/**
 * 作った要約の出来を確かめる(#25)。純粋関数(JVM 単体テスト可能)。
 * 問題があれば、呼び出し側(BookmarkAnalyzer)が、問題の直し方([feedback])を添えて1回だけ作り直させる。
 * 小さい端末内モデルは、本文を写す・同じ言葉を繰り返す・指定と違う言語で書く、といった崩れ方をしやすい。
 * 確かめるのは機械的に判断できることだけで、内容の良し悪しまでは見ない(根拠が見つからない文の割合は、極端な場合だけ見る)。
 */
object SummaryQuality {

    /**
     * @param summary 作った要約
     * @param source 要約の元の本文(タイトルを含めてよい)。空なら、本文との突き合わせ(写し・根拠)はしない
     * @param style 要約の言語と書き方
     * @param maxChars 要約の長さの上限(日本語の文字数。[AiPrompts.summaryMaxChars] に書き方を合わせたもの)
     * @return 見つかった問題。無ければ空
     */
    fun check(summary: String, source: String, style: SummaryStyle, maxChars: Int): List<SummaryProblem> {
        val text = summary.trim()
        val key = EvidenceFinder.matchKey(text)
        if (key.length < minChars(style.language)) return listOf(SummaryProblem.EMPTY)
        return buildList {
            if (REFUSAL.containsMatchIn(text)) add(SummaryProblem.REFUSED)
            if (!isWrittenIn(text, style.language)) add(SummaryProblem.WRONG_LANGUAGE)
            if (length(text, style.language) > limitOf(maxChars, style.language) * LENGTH_TOLERANCE) add(SummaryProblem.TOO_LONG)
            if (hasRepetition(key)) add(SummaryProblem.REPEATED)
            val sourceKey = EvidenceFinder.matchKey(source)
            if (sourceKey.length >= MIN_SOURCE_CHARS) {
                if (isCopied(key, sourceKey)) add(SummaryProblem.COPIED)
                if (isMostlyUnsupported(text, source)) add(SummaryProblem.UNSUPPORTED)
            }
        }
    }

    /** 作り直すときに、プロンプトへ足す直し方(問題ごとの [SummaryProblem.hint] をつなげたもの)。 */
    fun feedback(problems: List<SummaryProblem>): String = problems.joinToString("") { it.hint }

    /** 要約の長さ。英語は語数、それ以外は(空白を除いた)文字数。上限の数え方と同じ。 */
    internal fun length(text: String, language: SummaryLanguage): Int = when (language) {
        SummaryLanguage.ENGLISH -> text.split(WHITESPACE).count { it.isNotEmpty() }
        else -> text.count { !it.isWhitespace() }
    }

    /** [maxChars](日本語の文字数)に当たる、その言語での上限 */
    private fun limitOf(maxChars: Int, language: SummaryLanguage): Int = when (language) {
        SummaryLanguage.ENGLISH -> maxOf(1, (maxChars * ENGLISH_WORDS_PER_CHAR).toInt())
        else -> maxChars
    }

    private fun minChars(language: SummaryLanguage): Int = if (language == SummaryLanguage.ENGLISH) MIN_ENGLISH_KEY_CHARS else MIN_CJK_KEY_CHARS

    /** 指定の言語で書かれているか(文字の種類で見る。固有名詞や英単語が混ざっていても通す)。 */
    internal fun isWrittenIn(text: String, language: SummaryLanguage): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        fun ratio(predicate: (Char) -> Boolean) = letters.count(predicate).toFloat() / letters.length
        val kana = ratio { Character.UnicodeScript.of(it.code).let { s -> s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA } }
        val han = ratio { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val hangul = ratio { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL }
        val latin = ratio { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        return when (language) {
            // 日本語は、かなが入っていて、日本語の文字が一定以上(かなの無い漢字だけの文は中国語)
            SummaryLanguage.JAPANESE -> kana > 0f && kana + han >= MIN_SCRIPT_RATIO
            SummaryLanguage.CHINESE -> han >= MIN_SCRIPT_RATIO && kana < MAX_FOREIGN_KANA_RATIO
            SummaryLanguage.KOREAN -> hangul >= MIN_SCRIPT_RATIO
            SummaryLanguage.ENGLISH -> latin >= MIN_LATIN_RATIO
        }
    }

    /** 同じ 10 文字のつながりが 3 回以上出てくる、または同じ文が 2 回以上出てくる。 */
    internal fun hasRepetition(key: String): Boolean {
        if (key.length >= REPEAT_GRAM_CHARS * REPEAT_TIMES) {
            val counts = HashMap<String, Int>()
            for (gram in key.windowed(REPEAT_GRAM_CHARS)) {
                val count = (counts[gram] ?: 0) + 1
                if (count >= REPEAT_TIMES) return true
                counts[gram] = count
            }
        }
        return false
    }

    /** 本文を写しているか。要約(の文字)が、本文よりずっと短いのに、本文の中にそのまま入っている。 */
    private fun isCopied(summaryKey: String, sourceKey: String): Boolean =
        summaryKey.length >= MIN_COPY_CHARS && sourceKey.length >= summaryKey.length * COPY_SOURCE_RATIO && sourceKey.contains(summaryKey)

    /** 要約の文のほとんどに、本文の根拠がまったく見つからないか。突き合わせられないとき(言語が違うなど)は false。 */
    private fun isMostlyUnsupported(summary: String, source: String): Boolean {
        val report = EvidenceFinder.find(summary, source)
        if (!report.comparable || report.items.isEmpty()) return false
        val veryUnsupported = report.items.count { it.support < UNSUPPORTED_SUPPORT }
        return veryUnsupported * 100 >= report.items.size * UNSUPPORTED_PERCENT
    }

    private const val LENGTH_TOLERANCE = 1.6f
    private const val ENGLISH_WORDS_PER_CHAR = 0.6f
    private const val MIN_CJK_KEY_CHARS = 5
    private const val MIN_ENGLISH_KEY_CHARS = 12
    private const val MIN_SCRIPT_RATIO = 0.4f
    private const val MIN_LATIN_RATIO = 0.7f
    private const val MAX_FOREIGN_KANA_RATIO = 0.1f
    private const val REPEAT_GRAM_CHARS = 10
    private const val REPEAT_TIMES = 3
    private const val MIN_SOURCE_CHARS = 300
    private const val MIN_COPY_CHARS = 30
    private const val COPY_SOURCE_RATIO = 2

    /** 根拠の文字のつながりがこの割合に満たない文を、まったく根拠が無い文とする */
    private const val UNSUPPORTED_SUPPORT = 0.15f
    private const val UNSUPPORTED_PERCENT = 60

    private val WHITESPACE = Regex("""\s+""")

    /** 要約を断る書き出し(日本語・英語) */
    private val REFUSAL = Regex(
        "^\\s*(?:申し訳|すみません|ごめんなさい|要約(?:できません|することができません)|本文が(?:ありません|空)|" +
            "(?:i'?m|i am) sorry|i (?:cannot|can't|can not|am unable)|sorry|as an ai)",
        RegexOption.IGNORE_CASE
    )
}

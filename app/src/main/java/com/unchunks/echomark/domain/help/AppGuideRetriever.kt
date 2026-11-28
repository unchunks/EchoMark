package com.unchunks.echomark.domain.help

/**
 * 使い方の質問に近い節を [AppGuide] から選ぶ。Android に依存しない純粋な処理。
 *
 * 埋め込みモデルは使わない(端末内 LLM と埋め込みモデルを同時に読み込むとメモリが足りなくなるため)。
 * 節の数は少ないので、日本語の分かち書きをせずに済む「2文字ずつの重なり」と、節ごとのキーワードで点を付ける。
 */
object AppGuideRetriever {

    /** 端末内 AI の文脈は短いため、渡す節は少なくする */
    const val DEFAULT_LIMIT = 3

    /** クラウド API は文脈が長いため、近い節を多めに渡す(言い換えの質問でも答えの載った節を落としにくい) */
    const val CLOUD_LIMIT = 8

    /** 題名に出てくる2文字は、本文より重く数える(題名は節の主題そのものなので) */
    private const val TITLE_WEIGHT = 2

    /** キーワードが質問に含まれていたら、その文字数のこの倍を足す(言い換えでも見つけられるように) */
    private const val KEYWORD_WEIGHT = 3

    /**
     * [question] に近い順に、点の付いた節を最大 [limit] 件返す。
     * どれにも当たらなければ、アプリの概要の節(先頭)だけを返す(何も渡さないより、AI が案内しやすい)。
     */
    fun retrieve(
        question: String,
        sections: List<GuideSection> = AppGuide.sections,
        limit: Int = DEFAULT_LIMIT
    ): List<GuideSection> {
        val normalized = normalize(question)
        val questionGrams = bigrams(normalized)
        val ranked = sections
            .map { it to score(it, normalized, questionGrams) }
            .filter { it.second > 0 }
            // 同点なら案内の順(基本的なものが先)
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
        return ranked.ifEmpty { sections.take(1) }
    }

    /** AI に渡す形 "[n] 題名: 本文" に整える(番号は 1 から) */
    fun format(sections: List<GuideSection>): List<String> =
        sections.mapIndexed { index, section -> "[${index + 1}] ${section.title}: ${section.body}" }

    private fun score(section: GuideSection, question: String, questionGrams: Set<String>): Int {
        if (questionGrams.isEmpty()) return 0
        val titleGrams = bigrams(normalize(section.title))
        val bodyGrams = bigrams(normalize(section.body))
        val gramScore = questionGrams.sumOf { gram ->
            when (gram) {
                in titleGrams -> TITLE_WEIGHT
                in bodyGrams -> 1
                else -> 0
            }
        }
        val keywordScore = section.keywords
            .map(::normalize)
            .filter { it.isNotEmpty() && it in question }
            .sumOf { it.length * KEYWORD_WEIGHT }
        return gramScore + keywordScore
    }

    /** 大文字・小文字と、空白・記号の違いを無くす(「Wi-Fi」と「wifi」を同じに扱う) */
    internal fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /** 2文字ずつの重なり。1文字だけなら、その1文字 */
    internal fun bigrams(text: String): Set<String> = when {
        text.isEmpty() -> emptySet()
        text.length == 1 -> setOf(text)
        else -> text.windowed(2).toSet()
    }
}

package com.unchunks.echomark.ui.chat

import com.unchunks.components.composeui.ChatMarkdown
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.EvidenceFinder
import com.unchunks.echomark.domain.bookmark.model.SentenceEvidence

/** 回答の1文を、引用した資料と照らし合わせた結果。 */
enum class ClaimStatus {
    /** 引用した資料に根拠が見つかった */
    SUPPORTED,

    /** 引用した資料には無いが、別の資料に根拠が見つかった */
    OTHER_SOURCE,

    /** どの資料にも根拠が見つからなかった */
    NOT_FOUND,

    /** 照らし合わせられなかった(資料が削除済み・本文が無い・言語が違う・文が短い) */
    UNCHECKED
}

/**
 * 引用番号の付いた回答の1文。
 * @property cited 文に付いている引用番号(出てきた順)
 * @property betterSource [ClaimStatus.OTHER_SOURCE] のときの、根拠が見つかった資料の番号
 */
data class AnswerClaim(
    val sentence: String,
    val cited: List<Int>,
    val status: ClaimStatus,
    val betterSource: Int? = null
)

/**
 * 回答が引用した資料。
 * @property number 回答の中の引用番号
 * @property quote 回答の文の根拠になった一節。見つからなければ null
 */
data class CitedSource(val number: Int, val bookmarkId: Long, val quote: String?)

/**
 * 回答と資料の照らし合わせの結果。
 * @property sources 回答が引用した資料(回答に出てきた順)
 * @property uncitedNumbers 文脈に入れたが回答が引用しなかった資料の番号
 * @property claims 引用番号の付いた文(回答の順)
 * @property unknownNumbers 回答に出てくるが、対応する資料が無い番号
 */
data class AnswerReview(
    val sources: List<CitedSource> = emptyList(),
    val uncitedNumbers: List<Int> = emptyList(),
    val claims: List<AnswerClaim> = emptyList(),
    val unknownNumbers: List<Int> = emptyList()
) {
    /** 確かめてほしい文 */
    val flagged: List<AnswerClaim>
        get() = claims.filter { it.status == ClaimStatus.NOT_FOUND || it.status == ClaimStatus.OTHER_SOURCE }

    /** 資料と照らし合わせられた文の数 */
    val checkedCount: Int get() = claims.count { it.status != ClaimStatus.UNCHECKED }
}

/**
 * 回答の引用番号を読み取り、引用した文を資料の本文と照らし合わせる。純粋関数(JVM 単体テスト可能)。
 *
 * AI を呼ばずに [EvidenceFinder] の文字のつながりで比べるため、すぐ終わり、AI が使えないときも動く。言い換えの大きい文では
 * 根拠が見つからないことがあるので、[AnswerReview.flagged] は間違いの断定ではなく、確かめる目印として出す。
 * 文に引用番号が無いものは、一般的な知識として書かれた文かもしれないため、照らし合わせない。
 */
object AnswerReviewer {
    /** 照らし合わせる資料の文字数の上限(長い本文でも、1つの回答で処理が長引かないように) */
    private const val MAX_SOURCE_CHARS = 30_000

    /** 文の中の引用番号の印。Markdown の解析の後に、文に分けても番号が文の中に残るように使う(私用領域の文字) */
    private const val MARK_START = ''
    private const val MARK_END = ''
    private val MARK = Regex("""\s*$MARK_START(\d+)$MARK_END""")

    /**
     * @param referencedIds 回答の文脈に入れた資料の ID(n 番目が引用番号 n)
     * @param bookmarks 資料の bookmarkId -> ブックマーク。削除済みは含まれない
     */
    fun review(answer: String, referencedIds: List<Long>, bookmarks: Map<Long, Bookmark>): AnswerReview {
        val sentences = citingSentences(answer)
        val cited = sentences.flatMap { it.numbers }.distinct()
        val numbers = 1..referencedIds.size
        val evidence = evidenceBySource(sentences, referencedIds, bookmarks)
        val claims = sentences.map { claimOf(it, evidence) }
        return AnswerReview(
            sources = cited.filter { it in numbers && referencedIds[it - 1] in bookmarks }.map { number ->
                val quote = sentences.filter { number in it.numbers }
                    .mapNotNull { evidence[number]?.get(it.key) }
                    .filter { it.isSupported }
                    .maxByOrNull { it.support }
                    ?.quote
                CitedSource(number, referencedIds[number - 1], quote)
            },
            uncitedNumbers = numbers.filter { it !in cited && referencedIds[it - 1] in bookmarks },
            claims = claims,
            // 資料の次の番号は、その質問に添付したファイルのことがある(添付は保存しないため確かめられない)
            unknownNumbers = cited.filter { it !in 1..referencedIds.size + 1 }
        )
    }

    /** 引用番号の付いた文(回答の順)。文の後ろ(句点の後)に付いた番号も、その文の番号にする。 */
    internal fun citingSentences(answer: String): List<CitingSentence> =
        ChatMarkdown.parse(answer).flatMap { block ->
            when (block) {
                is ChatMarkdown.Block.Heading -> sentencesOf(block.spans)
                is ChatMarkdown.Block.Paragraph -> sentencesOf(block.spans)
                is ChatMarkdown.Block.ListItem -> sentencesOf(block.spans)
                is ChatMarkdown.Block.Code, ChatMarkdown.Block.Divider -> emptyList()
            }
        }.filter { it.numbers.isNotEmpty() }

    private fun sentencesOf(spans: List<ChatMarkdown.Span>): List<CitingSentence> {
        val marked = spans.joinToString("") { span ->
            span.citation?.let { "$MARK_START$it$MARK_END" } ?: span.text
        }
        val sentences = mutableListOf<CitingSentence>()
        for (part in EvidenceFinder.splitSentences(marked)) {
            val numbers = MARK.findAll(part).map { it.groupValues[1].toInt() }.toList()
            val text = part.replace(MARK, "").trim()
            val previous = sentences.lastOrNull()
            if (EvidenceFinder.matchKey(text).isEmpty() && previous != null) {
                // 「〜です。[1]」の番号だけが次の文に分かれたもの
                sentences[sentences.lastIndex] = previous.copy(numbers = (previous.numbers + numbers).distinct())
            } else {
                sentences += CitingSentence(text, numbers.distinct())
            }
        }
        return sentences
    }

    /** 資料の番号 -> (文の比べる形 -> 根拠)。照らし合わせられない資料は含まない。 */
    private fun evidenceBySource(
        sentences: List<CitingSentence>,
        referencedIds: List<Long>,
        bookmarks: Map<Long, Bookmark>
    ): Map<Int, Map<String, SentenceEvidence>> {
        if (sentences.isEmpty()) return emptyMap()
        val claims = sentences.joinToString("\n") { it.text }
        return referencedIds.withIndex().mapNotNull { (index, id) ->
            val source = bookmarks[id]?.let(::sourceText)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val report = EvidenceFinder.find(claims, source)
            if (report.comparable) (index + 1) to report.items.associateBy { EvidenceFinder.matchKey(it.sentence) } else null
        }.toMap()
    }

    /** AI に渡したのは要約と本文の一部のため、両方を資料として比べる */
    private fun sourceText(bookmark: Bookmark): String =
        listOfNotNull(bookmark.summary, bookmark.content?.take(MAX_SOURCE_CHARS))
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private fun claimOf(sentence: CitingSentence, evidence: Map<Int, Map<String, SentenceEvidence>>): AnswerClaim {
        val ownEvidence = sentence.numbers.mapNotNull { evidence[it]?.get(sentence.key) }
        if (ownEvidence.isEmpty()) return AnswerClaim(sentence.text, sentence.numbers, ClaimStatus.UNCHECKED)
        if (ownEvidence.any { it.isSupported }) return AnswerClaim(sentence.text, sentence.numbers, ClaimStatus.SUPPORTED)
        val better = evidence.entries
            .filter { it.key !in sentence.numbers }
            .mapNotNull { (number, items) -> items[sentence.key]?.takeIf { it.isSupported }?.let { number to it.support } }
            .maxByOrNull { it.second }
            ?.first
        val status = if (better != null) ClaimStatus.OTHER_SOURCE else ClaimStatus.NOT_FOUND
        return AnswerClaim(sentence.text, sentence.numbers, status, better)
    }

    /** 引用番号の付いた文。 */
    internal data class CitingSentence(val text: String, val numbers: List<Int>) {
        val key: String get() = EvidenceFinder.matchKey(text)
    }
}

package com.unchunks.echomark.domain.factcheck

import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/** Web で確かめる対象の種類。 */
enum class FactCheckTargetType {
    /** ブックマークの要約 */
    BOOKMARK_SUMMARY,

    /** チャットの回答 */
    CHAT_ANSWER
}

/** Web で確かめる対象(ブックマークの要約か、チャットの回答)。 */
data class FactCheckTarget(val type: FactCheckTargetType, val id: Long) {
    companion object {
        fun bookmark(id: Long) = FactCheckTarget(FactCheckTargetType.BOOKMARK_SUMMARY, id)
        fun chatAnswer(messageId: Long) = FactCheckTarget(FactCheckTargetType.CHAT_ANSWER, messageId)
    }
}

/**
 * 1つの主張を、Web のページと照らし合わせた結果。判定ではなく、確かめるための手がかり。
 * label は画面に出す日本語。
 */
enum class ClaimVerdict(val label: String) {
    /** 見つかったページに、同じことが書かれていた */
    SUPPORTED("裏付けあり"),

    /** 見つかったページに、違うことが書かれていた */
    CONFLICTING("食い違いあり"),

    /** 関係するページが見つからなかった・ページからは分からなかった */
    NOT_FOUND("見つからず")
}

/**
 * 主張の手がかりにしたページ。
 * @property quote ページの本文から引いた一節(ページを自分で読んで得たもの。検索サービスの概要ではない)。
 *   見つからなかったときに「調べたページ」として出すものは空
 */
data class FactCheckEvidence(
    val url: String,
    val title: String,
    val domain: String,
    val quote: String
)

/** 確かめた主張の1つ。 */
data class CheckedClaim(
    val claim: String,
    val verdict: ClaimVerdict,
    val evidence: List<FactCheckEvidence> = emptyList()
)

/**
 * Web で確かめた結果。開き直したときに検索し直さないよう保存する。
 * @property textHash 確かめた文章のハッシュ。今の文章と違えば、古い結果として出す([isStaleFor])
 */
data class FactCheckResult(
    val target: FactCheckTarget,
    val textHash: String,
    val claims: List<CheckedClaim>,
    val checkedAt: Long
) {
    /** [text] を確かめた結果ではない(確かめた後に要約・回答が変わった) */
    fun isStaleFor(text: String): Boolean = textHash != FactCheckText.hash(text)
}

/** 確かめている途中の段階。 */
sealed interface FactCheckProgress {
    /** 文章から事実の主張を取り出している */
    data object ExtractingClaims : FactCheckProgress

    /** [total] 件の主張のうち [index] 件目(1 始まり)を Web で探している */
    data class Searching(val index: Int, val total: Int) : FactCheckProgress
}

/** 確かめる文章が無い・主張が見つからなかった。 */
class NothingToFactCheckException : Exception("確かめられる事実の文が見つかりませんでした")

/** Web で確かめた結果の保存と、確かめる処理。 */
interface FactCheckRepository {
    /** 保存した結果。まだ確かめていなければ null */
    fun observe(target: FactCheckTarget): Flow<FactCheckResult?>

    /**
     * [text] から主張を取り出し、Web で探して照らし合わせ、結果を保存して返す(前の結果は置き換える)。
     * 要約・回答そのものは書き換えない。
     * @throws com.unchunks.echomark.domain.websearch.WebSearchException キーが無い・無効・上限など
     * @throws NothingToFactCheckException 確かめられる主張が無い
     */
    suspend fun check(target: FactCheckTarget, text: String, onProgress: (FactCheckProgress) -> Unit = {}): FactCheckResult
}

/** 確かめる文章の扱い(純粋関数)。 */
object FactCheckText {
    private val WHITESPACE = Regex("""\s+""")

    /** 空白の違いを無視した、文章のハッシュ(SHA-256 の16進) */
    fun hash(text: String): String {
        val normalized = text.replace(WHITESPACE, " ").trim()
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

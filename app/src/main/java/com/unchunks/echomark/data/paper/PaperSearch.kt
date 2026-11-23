package com.unchunks.echomark.data.paper

import com.unchunks.components.feed.FeedItem
import com.unchunks.components.feed.ParsedFeed
import com.unchunks.echomark.domain.subscription.PaperSource
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * 論文サイトのキーワード検索で、新着論文を購読する(arXiv・Europe PMC)。
 *
 * - 検索は「新しく投稿された順」に並べ、上から [RESULTS_PER_CHECK] 件を取る。新しいかどうかは、ふつうのフィードと同じく見た記事の目印で決める
 * - arXiv は Atom を返すので、ふつうのフィードと同じく読める。Europe PMC は JSON なので [parseEuropePmc] で読む
 * - キーワードは空白で区切り、すべてを含む論文を探す。`"..."` で囲むと、その語句のまま探す
 */
object PaperSearch {

    /** 入力のキーワード。[terms] は検索に使う語(小文字。語句は空白を含む)、[display] は画面とタグに使う形 */
    data class Keywords(val terms: List<String>, val display: String)

    /** 入力をキーワードにする。使える語が無ければ null */
    fun parseKeywords(input: String): Keywords? {
        val terms = TOKEN.findAll(input.take(MAX_INPUT_CHARS))
            .map { (it.groups[1]?.value ?: it.value).replace(SYNTAX_CHARS, " ").replace(Regex("\\s+"), " ").trim() }
            // 検索式の演算子として読まれないように、語として入れられた AND・OR・NOT は除く(すべての語を含むものを探す)
            .filter { it.isNotEmpty() && it !in OPERATORS }
            .map { it.take(MAX_TERM_CHARS).trim() }
            .distinctBy { it.lowercase() }
            .take(MAX_TERMS)
            .toList()
        if (terms.isEmpty()) return null
        return Keywords(
            terms = terms.map { it.lowercase() },
            display = terms.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
        )
    }

    /** 新着を確かめるための検索の URL(新しく投稿された順) */
    fun feedUrl(source: PaperSource, keywords: Keywords): String = when (source) {
        PaperSource.ARXIV -> {
            val query = keywords.terms.joinToString(" AND ") { "all:" + quoted(it) }
            "${PaperSource.ARXIV_API_URL}?search_query=${encode(query)}&sortBy=submittedDate&sortOrder=descending&max_results=$RESULTS_PER_CHECK"
        }
        PaperSource.PREPRINTS, PaperSource.PUBMED ->
            "${PaperSource.EUROPE_PMC_API_URL}?query=${encode(europePmcQuery(source, keywords))}" +
                "&format=json&resultType=lite&pageSize=$RESULTS_PER_CHECK&sort=${encode("P_PDATE_D desc")}"
    }

    /** 同じ検索を、サイトの検索画面で開く URL(利用者が結果を見るため) */
    fun searchPageUrl(source: PaperSource, keywords: Keywords): String = when (source) {
        PaperSource.ARXIV -> "https://arxiv.org/search/?query=${encode(keywords.display)}&searchtype=all&order=-announced_date_first"
        PaperSource.PREPRINTS, PaperSource.PUBMED -> "https://europepmc.org/search?query=${encode(europePmcQuery(source, keywords))}"
    }

    private fun europePmcQuery(source: PaperSource, keywords: Keywords): String {
        val filter = if (source == PaperSource.PUBMED) "SRC:MED" else "SRC:PPR"
        return "(" + keywords.terms.joinToString(" AND ") { quoted(it) } + ") AND $filter"
    }

    /**
     * arXiv の論文は版(`v1`・`v2`)ごとに URL と ID が変わる。版を除いて、同じ論文の新しい版が新着として保存されないようにする。
     * arXiv 以外の記事はそのまま。
     */
    fun normalizeArxivItems(items: List<FeedItem>): List<FeedItem> = items.map { item ->
        item.copy(key = stripArxivVersion(item.key), url = stripArxivVersion(item.url))
    }

    private fun stripArxivVersion(value: String): String =
        ARXIV_ABS.matchEntire(value)?.let { "https://arxiv.org/abs/${it.groupValues[1]}" } ?: value

    /** Europe PMC の検索の結果(JSON)を読む。検索の結果として読めなければ null */
    fun parseEuropePmc(bytes: ByteArray): ParsedFeed? {
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        val results = json.optJSONObject("resultList")?.optJSONArray("result") ?: return null
        val items = (0 until minOf(results.length(), MAX_RESULTS)).mapNotNull { index ->
            val result = results.optJSONObject(index) ?: return@mapNotNull null
            val id = result.str("id") ?: return@mapNotNull null
            val source = result.str("source") ?: return@mapNotNull null
            val url = articleUrl(source, id, result.str("pmid"), result.str("doi")) ?: return@mapNotNull null
            FeedItem(
                key = "europepmc:$source:$id".take(MAX_KEY_CHARS),
                url = url,
                title = result.str("title")?.let(::cleanTitle)?.takeIf { it.isNotEmpty() },
                publishedAt = parseDate(result.str("firstPublicationDate"))
            )
        }
        return ParsedFeed(isYouTube = false, title = "Europe PMC", siteUrl = "https://europepmc.org/", items = items)
    }

    /**
     * 論文のページの URL。PubMed の論文は PubMed のページ、DOI があれば DOI(出版社・プレプリントのサイトのページへ転送される)、
     * どちらも無ければ Europe PMC のページ。ページの本文を取得して要約するので、本文が HTML に入っているページを選ぶ。
     */
    private fun articleUrl(source: String, id: String, pmid: String?, doi: String?): String? {
        if (source == "MED" && pmid != null && pmid.all { it.isDigit() }) return "https://pubmed.ncbi.nlm.nih.gov/$pmid/"
        if (doi != null && doi.startsWith("10.") && doi.length <= MAX_DOI_CHARS && doi.none { it.isWhitespace() }) {
            safeUri("https", "doi.org", "/$doi")?.let { return it }
        }
        if (!source.all { it.isLetterOrDigit() } || !id.all { it.isLetterOrDigit() }) return null
        return "https://europepmc.org/article/$source/$id"
    }

    private fun safeUri(scheme: String, host: String, path: String): String? = try {
        URI(scheme, host, path, null).toASCIIString()
    } catch (e: Exception) {
        null
    }

    /** Europe PMC のタイトルは HTML の実体で書いたタグ(`&lt;i&gt;`)を含むので、実体を戻してからタグを除く */
    internal fun cleanTitle(raw: String): String {
        val unescaped = Parser.unescapeEntities(raw, false)
        val plain = if (unescaped.contains('<')) Jsoup.parse(unescaped).text() else unescaped
        return plain.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE_CHARS)
    }

    /** `2026-11-20` を、その日の 0 時(UTC)のエポックミリ秒にする */
    internal fun parseDate(raw: String?): Long? = try {
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    } catch (e: DateTimeParseException) {
        null
    }

    private fun JSONObject.str(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    private fun quoted(term: String): String = if (term.contains(' ')) "\"$term\"" else term

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /** 1回の確認で取る検索の結果の数(1回に保存する数より十分多く) */
    const val RESULTS_PER_CHECK = 50

    /** キーワードの語の数の上限 */
    const val MAX_TERMS = 8

    private const val MAX_INPUT_CHARS = 500
    private const val MAX_TERM_CHARS = 100
    private const val MAX_RESULTS = 100
    private const val MAX_TITLE_CHARS = 300
    private const val MAX_KEY_CHARS = 300
    private const val MAX_DOI_CHARS = 300

    /** `"語句"` か、空白で区切った語 */
    private val TOKEN = Regex("\"([^\"]*)\"|[^\\s\"]+")

    /** 検索式で意味を持つ文字(語の中にあれば空白にする) */
    private val SYNTAX_CHARS = Regex("[\"():\\[\\]{}^~*?\\\\]")
    private val OPERATORS = setOf("AND", "OR", "NOT", "ANDNOT")
    private val ARXIV_ABS = Regex("https?://(?:www\\.)?arxiv\\.org/abs/(.+?)v\\d+")
}

package com.unchunks.echomark.data.search

import com.unchunks.components.feed.FeedItem
import com.unchunks.components.feed.ParsedFeed
import com.unchunks.echomark.domain.subscription.QueryKind
import com.unchunks.echomark.domain.subscription.SearchSource
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * サイトの検索(論文・ニュース・技術記事・GitHub のリリース)で新着を購読するための、検索の URL の作り方と結果の読み方。
 *
 * - 検索は新しい順に並べ、上から取る。新しいかどうかは、ふつうのフィードと同じく見た記事の目印で決める
 * - JSON を返す Europe PMC 以外は RSS・Atom なので、ふつうのフィードと同じく読める
 * - キーワードは空白で区切り、すべてを含むものを探す。`"..."` で囲むと、その語句のまま探す
 * - 同じ検索を二重に購読しないよう、URL に入れる語は小文字にそろえる(画面とタグには入力のまま出す)
 */
object SearchFeeds {

    /** 入力のキーワード。[terms] は検索に使う語(小文字。語句は空白を含む)、[display] は画面とタグに使う形 */
    data class Keywords(val terms: List<String>, val display: String)

    /** 検索の購読を作った結果 */
    sealed interface Query {
        /** [feedUrl] で新着を確かめる。[siteUrl] は同じ検索を開くサイトの画面(無ければ null)、[title] は画面とタグに使う */
        data class Valid(val feedUrl: String, val siteUrl: String?, val title: String) : Query

        /** 入力が使えない。[message] は利用者にそのまま見せる */
        data class Invalid(val message: String) : Query
    }

    /** [source] で [kind] の [input] を探す購読を作る */
    fun build(source: SearchSource, kind: QueryKind, input: String): Query {
        if (kind !in source.kinds) return Query.Invalid("${source.label}では${kind.label}で探せません")
        return when (kind) {
            QueryKind.KEYWORDS -> parseKeywords(input)?.let { keywords(source, it) }
                ?: Query.Invalid("キーワードを入れてください")
            QueryKind.AUTHOR -> author(source, input)
            QueryKind.CATEGORY -> category(input)
            QueryKind.TAG -> tag(source, input)
            QueryKind.REPOSITORY -> repository(input)
        }
    }

    /** 入力をキーワードにする。使える語が無ければ null */
    fun parseKeywords(input: String): Keywords? {
        val terms = TOKEN.findAll(input.take(MAX_INPUT_CHARS))
            .map { cleanTerm(it.groups[1]?.value ?: it.value) }
            // 検索式の演算子として読まれないように、語として入れられた AND・OR・NOT は除く(すべての語を含むものを探す)
            .filter { it.isNotEmpty() && it !in OPERATORS }
            .map { it.take(MAX_TERM_CHARS).trim() }
            .distinctBy { it.lowercase() }
            .take(MAX_TERMS)
            .toList()
        if (terms.isEmpty()) return null
        return Keywords(terms = terms.map { it.lowercase() }, display = terms.joinToString(" ") { quoted(it) })
    }

    private fun keywords(source: SearchSource, keywords: Keywords): Query.Valid {
        val spaced = keywords.terms.joinToString(" ") { quoted(it) }
        return when (source) {
            SearchSource.ARXIV -> {
                val query = keywords.terms.joinToString(" AND ") { "all:" + quoted(it) }
                Query.Valid(
                    arxivUrl(query),
                    "https://arxiv.org/search/?query=${encode(keywords.display)}&searchtype=all&order=-announced_date_first",
                    keywords.display
                )
            }
            SearchSource.PREPRINTS, SearchSource.PUBMED -> {
                val query = "(" + keywords.terms.joinToString(" AND ") { quoted(it) } + ")"
                europePmc(source, query, keywords.display)
            }
            SearchSource.HATENA -> {
                val base = SearchSource.HATENA_SEARCH_URL + encodePath(spaced)
                Query.Valid("$base?mode=rss&sort=recent&target=text&users=$HATENA_MIN_USERS", "$base?sort=recent&target=text&users=$HATENA_MIN_USERS", keywords.display)
            }
            SearchSource.GDELT -> {
                // 日本語のキーワードは、日本語の記事に絞る(ほかの言語の記事は、日本語の語に当てはまっても読めないことが多い)
                val query = if (spaced.any { it.isJapanese() }) "$spaced sourcelang:japanese" else spaced
                Query.Valid(
                    "${SearchSource.GDELT_API_URL}?query=${encodePath(query)}&mode=artlist&format=rss&sort=datedesc" +
                        "&maxrecords=$RESULTS_PER_CHECK&timespan=14d",
                    null,
                    keywords.display
                )
            }
            SearchSource.HACKER_NEWS -> Query.Valid(
                "${SearchSource.HNRSS_URL}?q=${encode(spaced)}",
                "https://hn.algolia.com/?q=${encode(spaced)}&sort=byDate&type=story",
                keywords.display
            )
            // KEYWORDS で探せないサイトは build で断っている
            SearchSource.QIITA, SearchSource.ZENN, SearchSource.GITHUB_RELEASES -> error("unreachable")
        }
    }

    private fun author(source: SearchSource, input: String): Query {
        val name = cleanTerm(input.take(MAX_TERM_CHARS))
        val tokens = name.split(' ').filter { it.isNotEmpty() && it !in OPERATORS }
        if (tokens.isEmpty()) return Query.Invalid("著者の名前を入れてください")
        val display = tokens.joinToString(" ")
        return when (source) {
            // 名前の語をすべて含む著者(姓・名の順や、ミドルネームの有無によらず見つかる)
            SearchSource.ARXIV -> Query.Valid(
                arxivUrl(tokens.joinToString(" AND ") { "au:" + it.lowercase() }),
                "https://arxiv.org/search/?query=${encode(display)}&searchtype=author&order=-announced_date_first",
                display
            )
            else -> europePmc(source, "AUTH:\"${display.lowercase()}\"", display)
        }
    }

    private fun category(input: String): Query {
        val category = input.trim()
        if (!ARXIV_CATEGORY.matches(category)) return Query.Invalid("分野の記号を入れてください(例: cs.AI・cs.CL・stat.ML・quant-ph)")
        return Query.Valid(arxivUrl("cat:$category"), "https://arxiv.org/list/$category/new", category)
    }

    private fun tag(source: SearchSource, input: String): Query {
        val tag = input.trim().removePrefix("#").trim().lowercase()
        if (tag.isEmpty() || tag.length > MAX_TAG_CHARS || tag.any { it.isWhitespace() || it in "/?#%&\\" }) {
            return Query.Invalid("タグを1つ入れてください(空白や / を含まない名前。例: kotlin)")
        }
        val base = when (source) {
            SearchSource.ZENN -> SearchSource.ZENN_TOPICS_URL
            else -> SearchSource.QIITA_TAGS_URL
        } + encodePath(tag)
        return Query.Valid("$base/feed", base, tag)
    }

    private fun repository(input: String): Query {
        val path = input.trim()
            .replace(Regex("^(https?://)?(www\\.)?github\\.com/", RegexOption.IGNORE_CASE), "")
            .split('/').filter { it.isNotEmpty() }
        val owner = path.getOrNull(0)
        val repo = path.getOrNull(1)?.removeSuffix(".git")
        if (owner == null || repo == null || !GITHUB_OWNER.matches(owner) || !GITHUB_REPO.matches(repo) || repo == "." || repo == "..") {
            return Query.Invalid("owner/repo の形で入れてください(例: JetBrains/kotlin)")
        }
        // GitHub の URL は大文字小文字を区別しないので、二重に購読しないよう小文字にそろえる
        val base = "${SearchSource.GITHUB_URL}${owner.lowercase()}/${repo.lowercase()}"
        return Query.Valid("$base/releases.atom", "$base/releases", "$owner/$repo")
    }

    private fun arxivUrl(query: String): String =
        "${SearchSource.ARXIV_API_URL}?search_query=${encode(query)}&sortBy=submittedDate&sortOrder=descending&max_results=$RESULTS_PER_CHECK"

    private fun europePmc(source: SearchSource, query: String, title: String): Query.Valid {
        val filter = if (source == SearchSource.PUBMED) "SRC:MED" else "SRC:PPR"
        val full = "$query AND $filter"
        return Query.Valid(
            "${SearchSource.EUROPE_PMC_API_URL}?query=${encode(full)}" +
                "&format=json&resultType=lite&pageSize=$RESULTS_PER_CHECK&sort=${encode("P_PDATE_D desc")}",
            "https://europepmc.org/search?query=${encode(full)}",
            title
        )
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

    /** 検索式で意味を持つ文字を空白にし、空白をまとめる */
    private fun cleanTerm(raw: String): String = raw.replace(SYNTAX_CHARS, " ").replace(Regex("\\s+"), " ").trim()

    private fun quoted(term: String): String = if (term.contains(' ')) "\"$term\"" else term

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /** URL のパスや、空白を `+` で表せない問い合わせに入れる形(空白は `%20`) */
    private fun encodePath(value: String): String = encode(value).replace("+", "%20")

    /** ひらがな・カタカナ・漢字 */
    private fun Char.isJapanese(): Boolean = this in '぀'..'ヿ' || this in '㐀'..'鿿' || this in 'ｦ'..'ﾟ'

    /** 1回の確認で取る検索の結果の数(1回に保存する数より十分多く) */
    const val RESULTS_PER_CHECK = 50

    /** キーワードの語の数の上限 */
    const val MAX_TERMS = 8

    /** はてなブックマークで、この人数以上がブックマークした記事だけを探す(宣伝や個人のメモを除く) */
    const val HATENA_MIN_USERS = 3

    private const val MAX_INPUT_CHARS = 500
    private const val MAX_TERM_CHARS = 100
    private const val MAX_TAG_CHARS = 50
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

    /** arXiv の分野の記号(`cs.AI`・`quant-ph`・`cond-mat.mes-hall` など) */
    private val ARXIV_CATEGORY = Regex("[A-Za-z]+(?:-[A-Za-z]+)*(?:\\.[A-Za-z]+(?:-[A-Za-z]+)*)?")
    private val GITHUB_OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val GITHUB_REPO = Regex("[A-Za-z0-9._-]{1,100}")
}

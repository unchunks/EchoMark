package com.unchunks.echomark.data.discover

import com.unchunks.echomark.di.DispatcherProvider
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import javax.inject.Inject

/**
 * arXiv の API で、英語の論文を探す(キー不要。Atom フィードで返る)。
 * 検索語が複数の単語のときは、すべてを含むものを探す。
 *
 * @param baseUrl API の URL(`.../api/query`。テストではローカルのサーバーに差し替える)
 */
class ArxivSearchClient(
    private val client: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    private val baseUrl: HttpUrl
) : ResourceSearchClient {

    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, DEFAULT_BASE_URL)

    override val source = ResourceSource.PAPER

    override suspend fun search(query: String, limit: Int): List<ResourceCandidate> =
        withContext(dispatcherProvider.io) {
            val terms = query.split(WHITESPACE).filter { it.isNotEmpty() }
            if (terms.isEmpty()) return@withContext emptyList()
            val url = baseUrl.newBuilder()
                .addQueryParameter("search_query", terms.joinToString(" AND ") { "all:$it" })
                .addQueryParameter("start", "0")
                .addQueryParameter("max_results", limit.coerceIn(1, MAX_LIMIT).toString())
                .addQueryParameter("sortBy", "relevance")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SearchSupport.USER_AGENT)
                .header("Accept", "application/atom+xml, application/xml")
                .build()
            ArxivAtom.parse(SearchSupport.execute(client, request), limit)
        }

    companion object {
        private val DEFAULT_BASE_URL = "https://export.arxiv.org/api/query".toHttpUrl()
        private const val MAX_LIMIT = 50
        private val WHITESPACE = Regex("""[\s　]+""")
    }
}

/** arXiv の Atom フィードの読み取り(純粋関数。JVM 単体テスト可能)。 */
internal object ArxivAtom {

    /** 概要の頭に付ける著者の人数の上限(多いと概要の文字数を食う) */
    private const val MAX_AUTHORS = 3

    fun parse(xml: String, limit: Int = Int.MAX_VALUE): List<ResourceCandidate> {
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        val candidates = document.select("entry").mapNotNull { entry ->
            // 絶対 URL は http で返ることがあるので https にする
            val url = SearchSupport.childText(entry, "id")?.trim()
                ?.replaceFirst("http://", "https://")
                ?.takeIf { SearchSupport.isWebUrl(it) }
                ?: return@mapNotNull null
            val title = SearchSupport.normalizeWhitespace(SearchSupport.childText(entry, "title").orEmpty())
            val authors = SearchSupport.children(entry, "author")
                .mapNotNull { SearchSupport.childText(it, "name")?.let(SearchSupport::normalizeWhitespace) }
                .filter { it.isNotEmpty() }
            val summary = SearchSupport.normalizeWhitespace(SearchSupport.childText(entry, "summary").orEmpty())
            ResourceCandidate(
                url = url,
                title = title,
                snippet = SearchSupport.snippet(withAuthors(authors, summary)),
                source = ResourceSource.PAPER,
                siteName = SITE_NAME,
                publishedAt = SearchSupport.childText(entry, "published")?.trim()?.takeIf { it.isNotEmpty() },
                // 分野のカテゴリ(cs.PL など)をタグにする
                tags = SearchSupport.children(entry, "category")
                    .map { it.attr("term").trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            )
        }
        return SearchSupport.finish(candidates, limit)
    }

    /** 概要の頭に「著者A, 著者B ほか: 」を付ける(著者が分からなければ概要だけ) */
    private fun withAuthors(authors: List<String>, summary: String): String {
        if (authors.isEmpty()) return summary
        val names = authors.take(MAX_AUTHORS).joinToString(", ") + if (authors.size > MAX_AUTHORS) " ほか" else ""
        return "$names: $summary"
    }

    private const val SITE_NAME = "arXiv"
}

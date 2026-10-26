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
 * はてなブックマークの全文検索 RSS で、日本語の Web 記事を探す(人気順。3 users 以上のものだけ)。
 * 公式の API ではなく検索ページの RSS なので、形式が変わって読めなくなったら例外を投げる。
 *
 * @param baseUrl 検索ページの URL(`.../q/`。テストではローカルのサーバーに差し替える)
 */
class HatenaBookmarkSearchClient(
    private val client: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    private val baseUrl: HttpUrl
) : ResourceSearchClient {

    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, DEFAULT_BASE_URL)

    override val source = ResourceSource.ARTICLE

    override suspend fun search(query: String, limit: Int): List<ResourceCandidate> =
        withContext(dispatcherProvider.io) {
            // 検索語は URL のパスに入る(スペースや「/」はエンコードされる)
            val url = baseUrl.newBuilder()
                .addPathSegment(query)
                .addQueryParameter("mode", "rss")
                .addQueryParameter("target", "text")
                .addQueryParameter("sort", "popular")
                .addQueryParameter("users", MIN_USERS)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SearchSupport.USER_AGENT)
                .header("Accept", "application/rss+xml, application/xml")
                .build()
            HatenaRss.parse(SearchSupport.execute(client, request), limit)
        }

    companion object {
        private val DEFAULT_BASE_URL = "https://b.hatena.ne.jp/q/".toHttpUrl()

        /** ブックマークがこの人数以上の記事だけを探す(個人のメモのようなものを除く) */
        private const val MIN_USERS = "3"
    }
}

/** はてなブックマーク検索の RSS 1.0(RDF)の読み取り(純粋関数。JVM 単体テスト可能)。 */
internal object HatenaRss {

    /** ブックマーク数が多いものから並んでいる前提で、先頭から [limit] 件まで読む */
    fun parse(xml: String, limit: Int = Int.MAX_VALUE): List<ResourceCandidate> {
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        // チャンネル自体の title・link と区別するため、item の直下の要素だけを見る
        val candidates = document.select("item").mapNotNull { item ->
            val url = (item.attr("rdf:about").ifBlank { SearchSupport.childText(item, "link").orEmpty() }).trim()
            if (!SearchSupport.isWebUrl(url)) return@mapNotNull null
            val title = SearchSupport.normalizeWhitespace(SearchSupport.childText(item, "title").orEmpty())
            ResourceCandidate(
                url = url,
                title = title,
                snippet = SearchSupport.snippet(SearchSupport.childText(item, "description").orEmpty()),
                source = ResourceSource.ARTICLE,
                siteName = siteNameOf(title, url),
                popularity = SearchSupport.childText(item, "hatena:bookmarkcount")?.trim()?.toLongOrNull(),
                publishedAt = SearchSupport.childText(item, "dc:date")?.trim()?.takeIf { it.isNotEmpty() },
                thumbnailUrl = SearchSupport.childText(item, "hatena:imageurl")?.trim()
                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                tags = SearchSupport.children(item, "dc:subject")
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() && it != META_TAG_READ_LATER }
                    .distinct()
            )
        }
        return SearchSupport.finish(candidates, limit)
    }

    /**
     * サイト名。タイトルの末尾が「 - Qiita」「 | Zenn」のような短い区切り付きの名前ならそれを、
     * 無ければ URL のホスト名を使う(タイトルはそのまま残す)。
     */
    internal fun siteNameOf(title: String, url: String): String? {
        val trailing = TITLE_SEPARATORS
            .mapNotNull { separator -> title.lastIndexOf(separator).takeIf { it > 0 }?.let { it + separator.length } }
            .maxOrNull()
            ?.let { title.substring(it).trim() }
        if (!trailing.isNullOrEmpty() && trailing.length <= MAX_SITE_NAME_CHARS) return trailing
        return SearchSupport.hostOf(url)
    }

    /** ブックマークする人が自分用に付ける、内容と関係のないタグ */
    private const val META_TAG_READ_LATER = "あとで読む"

    private const val MAX_SITE_NAME_CHARS = 30
    private val TITLE_SEPARATORS = listOf(" - ", " | ", " – ", " — ", " ｜ ")
}

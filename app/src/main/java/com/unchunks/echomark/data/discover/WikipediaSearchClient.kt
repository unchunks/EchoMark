package com.unchunks.echomark.data.discover

import com.unchunks.echomark.di.DispatcherProvider
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

/**
 * 日本語版 Wikipedia の検索 API で、百科事典の項目を探す(キー不要の公開 API)。
 *
 * @param baseUrl API の URL(`.../w/api.php`。テストではローカルのサーバーに差し替える)
 */
class WikipediaSearchClient(
    private val client: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    private val baseUrl: HttpUrl
) : ResourceSearchClient {

    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, DEFAULT_BASE_URL)

    override val source = ResourceSource.ENCYCLOPEDIA

    override suspend fun search(query: String, limit: Int): List<ResourceCandidate> =
        withContext(dispatcherProvider.io) {
            val url = baseUrl.newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("list", "search")
                .addQueryParameter("srsearch", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("srlimit", limit.coerceIn(1, MAX_LIMIT).toString())
                .addQueryParameter("utf8", "1")
                .addQueryParameter("srprop", "snippet|timestamp")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SearchSupport.USER_AGENT)
                .header("Accept", "application/json")
                .build()
            WikipediaSearchJson.parse(SearchSupport.execute(client, request), limit)
        }

    companion object {
        private val DEFAULT_BASE_URL = "https://ja.wikipedia.org/w/api.php".toHttpUrl()

        /** API が一度に返す件数の上限(ボットでない利用者) */
        private const val MAX_LIMIT = 50
    }
}

/** Wikipedia の検索 API(list=search)の応答の読み取り(純粋関数。JVM 単体テスト可能)。 */
internal object WikipediaSearchJson {

    private const val ARTICLE_BASE = "https://ja.wikipedia.org/wiki/"

    fun parse(json: String, limit: Int = Int.MAX_VALUE): List<ResourceCandidate> {
        val results = JSONObject(json).optJSONObject("query")?.optJSONArray("search")
            ?: return emptyList()
        val candidates = (0 until results.length()).mapNotNull { index ->
            val entry = results.optJSONObject(index) ?: return@mapNotNull null
            val title = entry.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ResourceCandidate(
                url = articleUrl(title),
                title = title,
                // snippet には検索語を囲む <span class="searchmatch"> や実体参照が入っている
                snippet = SearchSupport.snippet(SearchSupport.plainText(entry.optString("snippet"))),
                source = ResourceSource.ENCYCLOPEDIA,
                siteName = SITE_NAME,
                publishedAt = entry.optString("timestamp").takeIf { it.isNotEmpty() }
            )
        }
        return SearchSupport.finish(candidates, limit)
    }

    /** 項目のページ URL。タイトルの空白は「_」にし、パーセントエンコードする。 */
    internal fun articleUrl(title: String): String =
        ARTICLE_BASE.toHttpUrl().newBuilder()
            .addPathSegment(title.trim().replace(' ', '_'))
            .build()
            .toString()

    private const val SITE_NAME = "Wikipedia"
}

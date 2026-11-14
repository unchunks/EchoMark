package com.unchunks.echomark.data.websearch

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.websearch.SearchUserAgents
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.websearch.WebSearchClient
import com.unchunks.echomark.domain.websearch.WebSearchException
import com.unchunks.echomark.domain.websearch.WebSearchResult
import com.unchunks.echomark.domain.websearch.WebSourceText
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brave Search API(Web Search)のクライアント。
 *
 * - エンドポイント: `GET https://api.search.brave.com/res/v1/web/search`、キーは `X-Subscription-Token` ヘッダー
 *   (<https://api-dashboard.search.brave.com/api-reference/web/search/get>)
 * - 通信は共通の OkHttpClient から作るため、オフラインモード([com.unchunks.echomark.data.network.NetworkGate])で止まる
 * - 時間制限を短くし、応答の大きさに上限を設ける。1秒あたりの回数は [WebSearchRateLimiter] で抑える
 * - 結果は信頼しない文字列として扱う(HTML のタグを除き、長さを切る)。保存はしない([WebSearchResult])
 * - キーはヘッダーにだけ入れ、ログ・例外のメッセージには出さない
 */
@Singleton
class BraveSearchClient internal constructor(
    okHttpClient: OkHttpClient,
    private val apiKeys: ApiKeyRepository,
    private val dispatcherProvider: DispatcherProvider,
    private val userAgents: SearchUserAgents,
    private val rateLimiter: WebSearchRateLimiter,
    /** API の URL(テストではローカルのサーバーに差し替える) */
    private val endpoint: HttpUrl
) : WebSearchClient {

    @Inject
    constructor(
        okHttpClient: OkHttpClient,
        apiKeys: ApiKeyRepository,
        dispatcherProvider: DispatcherProvider,
        userAgents: SearchUserAgents,
        rateLimiter: WebSearchRateLimiter
    ) : this(okHttpClient, apiKeys, dispatcherProvider, userAgents, rateLimiter, DEFAULT_ENDPOINT)

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun search(query: String, count: Int): List<WebSearchResult> {
        val key = apiKeys.getWebSearchKey()?.takeIf { it.isNotBlank() } ?: throw WebSearchException.KeyMissing()
        return request(key, query, count)
    }

    override suspend fun testKey(apiKey: String?) {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: apiKeys.getWebSearchKey()?.takeIf { it.isNotBlank() }
            ?: throw WebSearchException.KeyMissing()
        request(key, TEST_QUERY, 1)
    }

    private suspend fun request(key: String, query: String, count: Int): List<WebSearchResult> {
        val q = WebSourceText.queryFor(query)
        if (q.isEmpty()) return emptyList()
        rateLimiter.acquire()
        return withContext(dispatcherProvider.io) {
            val url = endpoint.newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("count", count.coerceIn(1, MAX_COUNT).toString())
                .addQueryParameter("safesearch", "moderate")
                // 概要に強調の印(<strong>)を入れない
                .addQueryParameter("text_decorations", "false")
                .addQueryParameter("result_filter", "web")
                .apply {
                    if (WebSourceText.hasJapanese(q)) {
                        addQueryParameter("country", "JP")
                        addQueryParameter("search_lang", "jp")
                    }
                }
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", userAgents.app)
                .header(TOKEN_HEADER, key)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw failureOf(response)
                val body = readCapped(response.body.byteStream())
                BraveSearchJson.parse(String(body, response.body.contentType()?.charset() ?: Charsets.UTF_8), count)
            }
        }
    }

    /** 失敗の応答を、画面に出せる例外にする。1秒あたりの上限なら、待つ時間を覚えておく */
    private fun failureOf(response: Response): WebSearchException {
        val errorCode = runCatching { readCapped(response.body.byteStream(), MAX_ERROR_BYTES) }
            .map { BraveSearchJson.errorCode(String(it, Charsets.UTF_8)) }
            .getOrNull()
        return when {
            response.code == 429 -> {
                val limits = BraveRateLimitHeaders.parse(response.header("X-RateLimit-Remaining"), response.header("X-RateLimit-Reset"))
                if (limits.monthlyExhausted || errorCode?.contains("QUOTA", ignoreCase = true) == true) {
                    WebSearchException.QuotaExceeded()
                } else {
                    val wait = limits.secondsUntilNextRequest ?: 1L
                    rateLimiter.pauseFor(wait * MILLIS_PER_SECOND)
                    WebSearchException.RateLimited(wait)
                }
            }
            response.code == 401 || response.code == 403 -> WebSearchException.InvalidKey()
            // キーが無効なとき、422 と SUBSCRIPTION_TOKEN_INVALID などのコードで返ることがある
            errorCode?.contains("TOKEN", ignoreCase = true) == true ||
                errorCode?.contains("SUBSCRIPTION", ignoreCase = true) == true -> WebSearchException.InvalidKey()
            else -> WebSearchException.Failed("HTTP ${response.code}")
        }
    }

    /** 上限まで読む。超えたら途中で切れた JSON になるため失敗にする */
    private fun readCapped(input: InputStream, maxBytes: Int = MAX_RESPONSE_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) throw WebSearchException.Failed("応答が大きすぎます")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private val DEFAULT_ENDPOINT = "https://api.search.brave.com/res/v1/web/search".toHttpUrl()
        private const val TOKEN_HEADER = "X-Subscription-Token"

        /** API が一度に返す件数の上限 */
        const val MAX_COUNT = 20

        /** キーを試すときの検索語 */
        private const val TEST_QUERY = "EchoMark"

        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 8L
        private const val CALL_TIMEOUT_SECONDS = 10L

        /** 応答の上限(20件と追加の抜粋でも 200KB ほど) */
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_ERROR_BYTES = 16 * 1024
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/** Brave Search API の応答の読み取り(純粋関数。JVM 単体テスト可能)。 */
internal object BraveSearchJson {
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_DESCRIPTION_CHARS = 400

    /** `web.results` の上位 [limit] 件。http/https 以外・URL かタイトルの無いものは除き、URL の重複も除く */
    fun parse(json: String, limit: Int = Int.MAX_VALUE): List<WebSearchResult> {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw WebSearchException.Failed("応答を読めません")
        }
        val results = root.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        val list = (0 until results.length()).mapNotNull { index ->
            val entry = results.optJSONObject(index) ?: return@mapNotNull null
            val url = entry.optString("url").trim()
            val domain = WebSourceText.domainOf(url) ?: return@mapNotNull null
            val title = plain(entry.optString("title")).take(MAX_TITLE_CHARS)
            if (title.isEmpty()) return@mapNotNull null
            WebSearchResult(
                url = url,
                title = title,
                description = plain(entry.optString("description")).take(MAX_DESCRIPTION_CHARS),
                domain = domain
            )
        }
        return list.distinctBy { it.url }.take(limit.coerceAtLeast(0))
    }

    /** 失敗の応答の `error.code`(読めなければ null) */
    fun errorCode(json: String): String? = runCatching {
        JSONObject(json).optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** HTML のタグと実体参照を除いたプレーンテキスト(text_decorations を切っても、念のため除く) */
    private fun plain(text: String): String =
        if (text.isBlank()) "" else Jsoup.parse(text).text().replace(Regex("""\s+"""), " ").trim()
}

/**
 * Brave Search API の上限のヘッダー。値は「1秒あたり, 月あたり」の順のカンマ区切り。
 * 例: `X-RateLimit-Remaining: 1, 1000`、`X-RateLimit-Reset: 1, 1419704`(それぞれの枠が戻るまでの秒数)
 */
internal data class BraveRateLimitHeaders(
    val monthlyExhausted: Boolean,
    val secondsUntilNextRequest: Long?
) {
    companion object {
        fun parse(remaining: String?, reset: String?): BraveRateLimitHeaders {
            val remainingValues = numbers(remaining)
            val resetValues = numbers(reset)
            // 月の枠が 0 なら、1秒待っても使えない
            val monthly = remainingValues.getOrNull(1) == 0L
            return BraveRateLimitHeaders(monthly, resetValues.firstOrNull()?.coerceIn(1L, MAX_WAIT_SECONDS))
        }

        private fun numbers(header: String?): List<Long> =
            header?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

        private const val MAX_WAIT_SECONDS = 60L
    }
}

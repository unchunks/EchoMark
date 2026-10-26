package com.unchunks.echomark.data.discover

import com.unchunks.echomark.di.DispatcherProvider
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

/**
 * YouTube の検索(InnerTube の search API)で、動画を探す。
 * 字幕の取得([com.unchunks.echomark.data.remote.YouTubeTranscriptFetcher])と同じ非公開の API なので、
 * 仕様が変わって応答の形が違うときは、空の結果ではなく例外を投げて呼び出し側に知らせる。
 *
 * @param baseUrl search API の URL(テストではローカルのサーバーに差し替える)
 */
class YouTubeSearchClient(
    private val client: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    private val baseUrl: HttpUrl
) : ResourceSearchClient {

    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, DEFAULT_BASE_URL)

    override val source = ResourceSource.VIDEO

    override suspend fun search(query: String, limit: Int): List<ResourceCandidate> =
        withContext(dispatcherProvider.io) {
            val body = JSONObject()
                .put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", CLIENT_NAME)
                            .put("clientVersion", CLIENT_VERSION)
                            .put("hl", "ja")
                            .put("gl", "JP")
                    )
                )
                .put("query", query)
                .toString()
            val request = Request.Builder()
                .url(baseUrl)
                .header("User-Agent", SearchSupport.USER_AGENT)
                .post(body.toRequestBody(JSON))
                .build()
            YouTubeSearchJson.parse(SearchSupport.execute(client, request), limit)
        }

    companion object {
        private val DEFAULT_BASE_URL = "https://www.youtube.com/youtubei/v1/search".toHttpUrl()
        private const val CLIENT_NAME = "WEB"
        private const val CLIENT_VERSION = "2.20250101.00.00"
        private val JSON = "application/json".toMediaType()
    }
}

/** YouTube の検索結果(InnerTube の応答)の読み取り(純粋関数。JVM 単体テスト可能)。 */
internal object YouTubeSearchJson {

    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{6,20}""")
    private val DIGITS = Regex("""[0-9][0-9,]*""")

    /**
     * 応答の中の `videoRenderer` を順に集めて候補にする。
     * レイアウトの変更に強いよう、決まった階層をたどらず JSON 全体を探す(ショート・広告・チャンネルは別の名前なので入らない)。
     */
    fun parse(json: String, limit: Int = Int.MAX_VALUE): List<ResourceCandidate> {
        val root = JSONObject(json)
        // 検索結果は必ず contents に入る。無ければ形式が変わった(または別のエラー応答)とみなす
        if (!root.has("contents")) throw IOException("unexpected YouTube response")
        val renderers = mutableListOf<JSONObject>()
        collectVideoRenderers(root, renderers, collectCap(limit))
        return SearchSupport.finish(renderers.mapNotNull(::toCandidate), limit)
    }

    /** 集める数の上限。重複や ID 不正で減る分の余裕を見る(Int の範囲を超えないようにする) */
    private fun collectCap(limit: Int): Int =
        if (limit >= Int.MAX_VALUE - EXTRA) Int.MAX_VALUE else limit.coerceAtLeast(0) + EXTRA

    /** [node] 以下の `videoRenderer` を、表示順に [out] へ [cap] 件まで集める */
    private fun collectVideoRenderers(node: Any?, out: MutableList<JSONObject>, cap: Int) {
        if (out.size >= cap) return
        when (node) {
            is JSONObject -> {
                for (key in node.keys()) {
                    val value = node.opt(key)
                    if (key == "videoRenderer" && value is JSONObject) out += value
                    else collectVideoRenderers(value, out, cap)
                    if (out.size >= cap) return
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                collectVideoRenderers(node.opt(i), out, cap)
                if (out.size >= cap) return
            }
        }
    }

    private fun toCandidate(renderer: JSONObject): ResourceCandidate? {
        val videoId = renderer.optString("videoId").takeIf { VIDEO_ID.matches(it) } ?: return null
        val title = text(renderer.optJSONObject("title")).orEmpty()
        val length = text(renderer.optJSONObject("lengthText"))
        val viewCount = text(renderer.optJSONObject("viewCountText"))
        val snippet = renderer.optJSONArray("detailedMetadataSnippets")
            ?.optJSONObject(0)
            ?.let { text(it.optJSONObject("snippetText")) }
            ?: summaryOf(length, viewCount)
        return ResourceCandidate(
            url = "https://www.youtube.com/watch?v=$videoId",
            title = SearchSupport.normalizeWhitespace(title),
            snippet = SearchSupport.snippet(snippet),
            source = ResourceSource.VIDEO,
            siteName = renderer.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)
                ?.optString("text")?.trim()?.takeIf { it.isNotEmpty() },
            popularity = viewCount?.let(::viewsOf),
            publishedAt = text(renderer.optJSONObject("publishedTimeText")),
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        )
    }

    /** `simpleText` か `runs[].text` をつないだ文字列(どちらも無ければ null) */
    private fun text(node: JSONObject?): String? {
        node ?: return null
        node.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = node.optJSONArray("runs") ?: return null
        return (0 until runs.length())
            .joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            .takeIf { it.isNotBlank() }
    }

    /** 「1,826回視聴」→ 1826。数字が無い(「視聴なし」など)ときは null。 */
    internal fun viewsOf(viewCountText: String): Long? =
        DIGITS.find(viewCountText)?.value?.replace(",", "")?.toLongOrNull()

    /** 動画の説明が無いときの代わりの概要(例: 「長さ 10:26 ・ 1,826回視聴」) */
    private fun summaryOf(length: String?, viewCount: String?): String =
        listOfNotNull(length?.let { "長さ $it" }, viewCount).joinToString(" ・ ")

    private const val EXTRA = 10
}

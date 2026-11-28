package com.unchunks.echomark.data.remote

import com.unchunks.components.core.readAllWithin
import com.unchunks.components.web.CaptionFileText
import com.unchunks.components.web.CaptionTranscript
import com.unchunks.echomark.domain.video.PeerTubeUrls
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * PeerTube の動画の情報と字幕を、そのサーバーの公開の REST API(認証なし)から取る。
 * - 動画の情報: `GET /api/v1/videos/{id}`(名前・説明・チャンネル・タグ・サムネイル)
 * - 字幕の一覧: `GET /api/v1/videos/{id}/captions`。字幕のファイル(WebVTT)は `fileUrl`(7.1〜)か `captionPath`(古い版)
 *
 * どれも PeerTube がだれにでも見せている公開の API で、動画の配信(ストリーム)には触れない(`docs/video-offline.md`)。
 * PeerTube でない・非公開・失敗のときは null を返し、呼び出し側はページの HTML から取った内容のまま続ける。
 *
 * [client] は、ローカルネットワークに接続しない設定の接続(UrlContentFetcher と同じ)。リダイレクトは追わない。
 */
class PeerTubeFetcher(
    private val client: OkHttpClient,
    private val userAgent: String
) {
    /** 取得した動画。[video] は本文の組み立て(HtmlContentExtractor.youtubeText)に渡す形 */
    data class Result(
        val video: YouTubeVideo,
        val transcript: CaptionTranscript?,
        /** サムネイル(大きい方の画像)の絶対 URL。無ければ null */
        val thumbnailUrl: String?,
        /** 投稿者が動画のダウンロードを許しているか(`downloadEnabled`。無ければ許していない扱い) */
        val downloadEnabled: Boolean = false,
        /** ダウンロードできる動画のファイル(Web Video の `files` と、HLS の `streamingPlaylists[].files`) */
        val files: List<File> = emptyList()
    )

    /**
     * 動画のファイル1つ。[url] は `fileDownloadUrl`(添付ファイルとして返す URL)、無ければ `fileUrl`。
     * [height] は画質(解像度の id。音声だけなら 0)
     */
    data class File(val url: String, val height: Int, val label: String?, val size: Long?)

    /** 動画の情報を取る。[withTranscript] が false なら字幕は取らない(ダウンロードの準備のとき) */
    fun fetch(ref: PeerTubeUrls.VideoRef, withTranscript: Boolean = true): Result? {
        val origin = ref.origin.toHttpUrlOrNull() ?: return null
        val details = get(origin, "api/v1/videos/${ref.videoId}")?.let { parseJson(it) } ?: return null
        // PeerTube の動画の答えか(同じパスの、ほかのサイトのページを取り違えない)
        val name = details.optString("name").takeIf { it.isNotBlank() && details.has("uuid") } ?: return null
        val video = YouTubeVideo(
            videoId = details.optString("uuid").takeIf { it.isNotBlank() },
            title = name,
            author = details.optJSONObject("channel")?.optString("displayName")?.takeIf { it.isNotBlank() }
                ?: details.optJSONObject("account")?.optString("displayName")?.takeIf { it.isNotBlank() },
            description = details.optString("description").takeIf { it.isNotBlank() && it != "null" },
            keywords = details.optJSONArray("tags").strings()
        )
        // 配信中(ライブ)の動画は字幕が無く、内容も変わっていくので、情報だけにする
        val isLive = details.optBoolean("isLive")
        val transcript = if (isLive || !withTranscript) null else transcript(origin, ref.videoId)
        return Result(
            video = video,
            transcript = transcript,
            thumbnailUrl = thumbnailUrl(origin, details),
            downloadEnabled = !isLive && details.optBoolean("downloadEnabled", false),
            files = if (isLive) emptyList() else files(origin, details)
        )
    }

    /** ダウンロードできるファイル。映像の無いもの(音声だけ)・接続してはいけない URL は除く */
    private fun files(origin: HttpUrl, details: JSONObject): List<File> {
        val playlists = details.optJSONArray("streamingPlaylists")
        val arrays = listOfNotNull(details.optJSONArray("files")) +
            (0 until (playlists?.length() ?: 0)).mapNotNull { playlists?.optJSONObject(it)?.optJSONArray("files") }
        return arrays.flatMap { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
            .filter { it.optBoolean("hasVideo", true) }
            .mapNotNull { file ->
                val raw = file.optString("fileDownloadUrl").takeIf { it.isNotBlank() && it != "null" }
                    ?: file.optString("fileUrl").takeIf { it.isNotBlank() && it != "null" }
                    ?: return@mapNotNull null
                val url = origin.resolve(raw)?.takeIf { isAllowedFileUrl(origin, it) } ?: return@mapNotNull null
                val resolution = file.optJSONObject("resolution")
                File(
                    url = url.toString(),
                    height = resolution?.optInt("id") ?: 0,
                    label = resolution?.optString("label")?.takeIf { it.isNotBlank() },
                    size = file.optLong("size", -1L).takeIf { it > 0 }
                )
            }
            .filter { it.height > 0 }
    }

    /** 字幕を1つ選んで取る。日本語 → 人が付けたもの → 最初のもの の順に選ぶ */
    private fun transcript(origin: HttpUrl, videoId: String): CaptionTranscript? {
        val data = get(origin, "api/v1/videos/$videoId/captions")?.let { parseJson(it) }?.optJSONArray("data") ?: return null
        val captions = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        val chosen = captions.sortedWith(
            compareByDescending<JSONObject> { it.optJSONObject("language")?.optString("id") == "ja" }
                .thenBy { it.optBoolean("automaticallyGenerated") }
        ).firstOrNull() ?: return null
        val fileUrl = chosen.optString("fileUrl").takeIf { it.isNotBlank() && it != "null" }?.let { origin.resolve(it) }
            ?: chosen.optString("captionPath").takeIf { it.isNotBlank() && it != "null" }?.let { origin.resolve(it) }
            ?: return null
        if (!isAllowedFileUrl(origin, fileUrl)) return null
        val text = download(fileUrl)?.let { CaptionFileText.parse(it) }?.takeIf { it.isNotBlank() } ?: return null
        return CaptionTranscript(
            text = text,
            languageCode = chosen.optJSONObject("language")?.optString("id").orEmpty(),
            isAutoGenerated = chosen.optBoolean("automaticallyGenerated")
        )
    }

    /** サムネイル。新しい版の `thumbnails`(いちばん大きいもの)、古い版の `previewPath`・`thumbnailPath` の順 */
    private fun thumbnailUrl(origin: HttpUrl, details: JSONObject): String? {
        val thumbnails = details.optJSONArray("thumbnails")
        val largest = (0 until (thumbnails?.length() ?: 0)).mapNotNull { thumbnails?.optJSONObject(it) }
            .maxByOrNull { it.optInt("width") }
            ?.optString("fileUrl")?.takeIf { it.isNotBlank() }
        val path = largest
            ?: details.optString("previewPath").takeIf { it.isNotBlank() && it != "null" }
            ?: details.optString("thumbnailPath").takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        return origin.resolve(path)?.takeIf { isAllowedFileUrl(origin, it) }?.toString()
    }

    /** 応答に書かれた URL へ接続してよいか。同じサーバーか、https の別のサーバー(オブジェクトストレージなど)だけ */
    private fun isAllowedFileUrl(origin: HttpUrl, url: HttpUrl): Boolean =
        (url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) || url.isHttps

    private fun get(origin: HttpUrl, path: String): String? {
        val url = origin.newBuilder().encodedPath("/").addEncodedPathSegments(path).build()
        return execute(Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build())
    }

    private fun download(url: HttpUrl): String? =
        execute(Request.Builder().url(url).header("User-Agent", userAgent).build())

    private fun execute(request: Request): String? = try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.isRedirect) return null
            // 上限を超える応答は途中で切れた JSON・字幕になるので使わない
            val bytes = response.body.byteStream().readAllWithin(MAX_BYTES) ?: return null
            String(bytes, response.body.contentType()?.charset() ?: Charsets.UTF_8)
        }
    } catch (_: IOException) {
        null
    }

    private fun parseJson(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: JSONException) {
        null
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    companion object {
        private const val MAX_BYTES = 2L * 1024 * 1024
    }
}

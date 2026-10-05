package com.unchunks.echomark.data.remote

import com.unchunks.echomark.data.attachment.extensionOf
import com.unchunks.echomark.data.attachment.fallbackMimeTypeOf
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.MAX_DOWNLOAD_BYTES
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.bookmark.model.isDownloadableMimeType
import com.unchunks.echomark.domain.bookmark.model.normalizeMimeType
import com.unchunks.echomark.domain.bookmark.model.titleFromFileName
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** URL先から取得したページ情報 */
data class FetchedContent(
    val title: String?,
    val description: String?,
    val text: String,
    /** OG 画像の絶対 URL(http/https のみ。無ければ null) */
    val imageUrl: String? = null,
    /** サイト名(og:site_name など。無ければ null) */
    val siteName: String? = null,
    /** YouTube の動画ページなら、ページ内の JSON から取り出した動画情報(字幕の取得と本文の組み立てに使う) */
    val youtube: YouTubeVideo? = null,
    /** リンク先が PDF・画像・音声・動画で、ダウンロードして保存したファイル(HTML なら null) */
    val file: StoredAttachment? = null
)

/** リンク先が PDF・画像・音声・動画だったときに、本体を保存する先。 */
fun interface DownloadSink {
    /** [body] を保存する(呼び出し元のスレッドで読む)。[maxBytes] を超えたら例外を投げて止める。 */
    fun save(body: InputStream, mimeType: String, fileName: String?, maxBytes: Long): StoredAttachment
}

/** YouTube のウォッチページ(インライン JSON の videoDetails)から取り出した動画情報。 */
data class YouTubeVideo(
    val videoId: String?,
    val title: String?,
    val author: String?,
    val description: String?,
    val keywords: List<String>
)

/**
 * OkHttp + Jsoup で Web ページのタイトル・概要・本文を取得する。
 * ローカルネットワーク・ループバックのアドレスには接続しない([isPublicAddress])。リダイレクトは自前で追い、
 * 各段で http/https 以外と https → http の格下げを拒否する。
 * リンク先が PDF・画像・音声・動画なら、保存先([DownloadSink])を渡されたときだけ本体をダウンロードする
 * (大きなファイルは時間がかかるため、全体の時間制限を長くした接続で取り直す。接続先の制限は同じ)。
 */
@Singleton
class UrlContentFetcher internal constructor(
    okHttpClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    /** YouTube の字幕の取得先を差し替える(テスト用)。null なら本物の YouTube */
    youtubePlayerEndpoint: HttpUrl? = null,
    /** 接続してよいアドレスか(テストではローカルのサーバーを許可する) */
    isAllowedAddress: (InetAddress) -> Boolean
) {
    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, isAllowedAddress = ::isPublicAddress)

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .dns(PublicOnlyDns(okHttpClient.dns, isAllowedAddress))
        .addNetworkInterceptor(PublicAddressInterceptor(isAllowedAddress))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** ファイルのダウンロード用(全体の時間制限を長くする。アドレスの制限・リダイレクトの扱いは [client] と同じ) */
    private val downloadClient: OkHttpClient = client.newBuilder()
        .callTimeout(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val transcriptFetcher = youtubePlayerEndpoint
        ?.let { YouTubeTranscriptFetcher(client, it) }
        ?: YouTubeTranscriptFetcher(client)

    /**
     * 失敗時は Result.failure(例外) を返す。呼び出し側はタイトル=URLのまま続行できる。
     * YouTube の動画ページなら字幕も取得して本文に入れる(字幕が取れなくてもページの取得は成功として返す)。
     * [downloadSink] を渡すと、リンク先が PDF・画像・音声・動画のとき本体を保存して [FetchedContent.file] に入れる
     * (渡さなければ HTML 以外は失敗)。
     */
    suspend fun fetch(url: String, downloadSink: DownloadSink? = null): Result<FetchedContent> =
        withContext(dispatcherProvider.io) {
            runCatching { withTranscript(fetchBlocking(url, downloadSink)) }
        }

    /** YouTube なら字幕を取得し、チャンネル名・説明文・字幕をまとめた本文に差し替える */
    private fun withTranscript(content: FetchedContent): FetchedContent {
        val video = content.youtube ?: return content
        val videoId = video.videoId ?: return content
        val transcript = transcriptFetcher.fetch(videoId) ?: return content
        val text = HtmlContentExtractor.youtubeText(video, transcript) ?: return content
        // 本文に説明文と字幕が入るので、汎用の概要(og:description)は重複させない
        return content.copy(text = text, description = null)
    }

    private fun fetchBlocking(url: String, downloadSink: DownloadSink?): FetchedContent {
        require(url.startsWith("http://") || url.startsWith("https://")) { "unsupported scheme: $url" }

        var current = url.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) {
            when (val step = fetchOnce(current, allowFile = downloadSink != null)) {
                is FetchStep.Done -> return step.content
                is FetchStep.Redirect -> current = step.next
                is FetchStep.File -> return download(step.url, downloadSink!!)
            }
        }
        throw IOException("too many redirects")
    }

    private sealed interface FetchStep {
        class Done(val content: FetchedContent) : FetchStep
        class Redirect(val next: HttpUrl) : FetchStep
        /** HTML ではなくダウンロードして保存するファイルだった(リダイレクトを追った後の URL) */
        class File(val url: HttpUrl) : FetchStep
    }

    /**
     * リンク先のファイルをダウンロードして保存する。最初の取得とは別の(時間制限の長い)接続で取り直す。
     * 途中でリダイレクトされる・種類が変わる・大きすぎるときは失敗。
     */
    private fun download(url: HttpUrl, sink: DownloadSink): FetchedContent {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (response.isRedirect || !response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val mimeType = fileMimeType(body.contentType(), url)
            if (!isDownloadableMimeType(mimeType)) throw IOException("unsupported content type: ${body.contentType()}")
            val length = body.contentLength()
            if (length > MAX_DOWNLOAD_BYTES) throw IOException("file too large: $length bytes")
            val fileName = fileNameOf(response.header("Content-Disposition"), url)
            val stored = sink.save(body.byteStream(), mimeType!!, fileName, MAX_DOWNLOAD_BYTES)
            return FetchedContent(
                title = fileName?.let { titleFromFileName(it) },
                description = null,
                text = "",
                file = stored
            )
        }
    }

    /** 1回分の取得。リダイレクトなら次の URL を、そうでなければ取得した内容を返す。 */
    private fun fetchOnce(url: HttpUrl, allowFile: Boolean): FetchStep {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                val next = response.header("Location")?.let { url.resolve(it) }
                    ?: throw IOException("invalid redirect")
                if (!isAllowedRedirect(url, next)) throw IOException("redirect not allowed: ${url.scheme} -> ${next.scheme}")
                return FetchStep.Redirect(next)
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val mediaType = body.contentType()
            if (mediaType != null && mediaType.subtype != "html" && mediaType.subtype != "xhtml+xml") {
                // PDF・画像・音声・動画なら、本文を読まずに閉じてダウンロードし直す
                if (allowFile && isDownloadableMimeType(fileMimeType(mediaType, url))) return FetchStep.File(url)
                throw IOException("unsupported content type: $mediaType")
            }

            val bytes = readCapped(body.byteStream())
            val document = Jsoup.parse(
                bytes.inputStream(),
                mediaType?.charset()?.name(),
                response.request.url.toString()
            )
            return FetchStep.Done(HtmlContentExtractor.extract(document))
        }
    }

    /** 上限サイズまでだけ読み込む(巨大レスポンス対策) */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < MAX_BODY_BYTES) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, minOf(read, MAX_BODY_BYTES - out.size()))
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val DOWNLOAD_TIMEOUT_MINUTES = 10L
        private const val DOWNLOAD_READ_TIMEOUT_SECONDS = 30L

        /**
         * ファイルの MIME タイプ。汎用の application/octet-stream・不明なら URL の拡張子から推定する。
         */
        internal fun fileMimeType(mediaType: MediaType?, url: HttpUrl): String? {
            val reported = normalizeMimeType(mediaType?.let { "${it.type}/${it.subtype}" })
            if (reported != null && reported != "application/octet-stream" && reported != "binary/octet-stream") return reported
            return fallbackMimeTypeOf(extensionOf(url.pathSegments.lastOrNull())) ?: reported
        }

        /**
         * 保存するファイル名。Content-Disposition の filename*(RFC 5987)・filename、無ければ URL の最後の部分。
         */
        internal fun fileNameOf(contentDisposition: String?, url: HttpUrl): String? {
            val params = contentDisposition?.split(';')?.map { it.trim() }.orEmpty()
            val extended = params.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.let { value ->
                    // 例: UTF-8''%E8%B3%87%E6%96%99.pdf
                    val charset = value.substringBefore("'", "").ifEmpty { "UTF-8" }
                    val encoded = value.substringAfterLast("'")
                    runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), charset) }.getOrNull()
                }
            val plain = params.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.removeSurrounding("\"")
            return (extended ?: plain ?: url.pathSegments.lastOrNull())
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) EchoMark/1.0"
    }
}

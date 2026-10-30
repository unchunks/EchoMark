package com.unchunks.echomark.data.remote

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.web.PageLink
import com.unchunks.components.web.PageLinks
import com.unchunks.components.web.PublicAddressInterceptor
import com.unchunks.components.web.PublicOnlyDns
import com.unchunks.components.web.YouTubeTranscriptFetcher
import com.unchunks.components.web.isAllowedRedirect
import com.unchunks.components.web.isPublicAddress
import com.unchunks.echomark.data.attachment.extensionOf
import com.unchunks.echomark.data.attachment.fallbackMimeTypeOf
import com.unchunks.echomark.data.extract.TextFileReader
import com.unchunks.echomark.domain.bookmark.model.GitHubFileRef
import com.unchunks.echomark.domain.bookmark.model.GitHubUrls
import com.unchunks.echomark.domain.bookmark.model.MAX_DOWNLOAD_BYTES
import com.unchunks.echomark.domain.bookmark.model.StageProgressReporter
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.bookmark.model.PageAccessLimit
import com.unchunks.echomark.domain.bookmark.model.isDownloadableMimeType
import com.unchunks.echomark.domain.bookmark.model.normalizeMimeType
import com.unchunks.echomark.domain.bookmark.model.titleFromFileName
import com.unchunks.echomark.domain.extract.ExtractedContent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
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
    val file: StoredAttachment? = null,
    /** ページ内のリンク(資料を探すときの候補。HTML 以外や、リンクを取らない取得では空) */
    val links: List<PageLink> = emptyList(),
    /** 有料・会員限定・ボット対策などで全文を見せてくれなかったページの、その理由(通常のページは null) */
    val accessLimit: PageAccessLimit? = null
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
    /** GitHub の API の取得先を差し替える(テスト用)。null なら本物の GitHub */
    githubApiEndpoint: HttpUrl? = null,
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
        ?.let { YouTubeTranscriptFetcher(client, it, USER_AGENT) }
        ?: YouTubeTranscriptFetcher(client, userAgent = USER_AGENT)

    private val githubFetcher = githubApiEndpoint
        ?.let { GitHubRepoFetcher(client, USER_AGENT, it) }
        ?: GitHubRepoFetcher(client, USER_AGENT)

    /**
     * 失敗時は Result.failure(例外) を返す。呼び出し側はタイトル=URLのまま続行できる。
     * YouTube の動画ページなら字幕も取得して本文に入れる(字幕が取れなくてもページの取得は成功として返す)。
     * [downloadSink] を渡すと、リンク先が PDF・画像・音声・動画のとき本体を保存して [FetchedContent.file] に入れる
     * (渡さなければ HTML 以外は失敗)。
     */
    suspend fun fetch(url: String, downloadSink: DownloadSink? = null): Result<FetchedContent> {
        // ダウンロードの進み具合の報告先(ワーカーが文脈に入れていれば)。取得はブロッキングで行うため先に取り出す
        val progress = currentCoroutineContext()[StageProgressReporter]
        return withContext(dispatcherProvider.io) {
            runCatching {
                // GitHub のコードのファイルは、ページの HTML ではなく中身をそのまま取る(取れなければ通常のページとして取る)
                val codeFile = GitHubUrls.codeFileOf(url)
                codeFile?.let { runCatching { withGitHubFile(it, fetchBlocking(it.rawUrl, null, progress)) }.getOrNull() }
                    ?: withGitHubRepo(url, withTranscript(fetchBlocking(url, downloadSink, progress)))
            }
        }
    }

    /**
     * GitHub のリポジトリのページなら、API の情報と README で本文とタイトルを差し替える(#14)。
     * 取れなければ(上限・非公開など)、ページの HTML から取った内容のまま。画像(OG 画像)・サイト名はページのものを残す。
     */
    internal fun withGitHubRepo(url: String, content: FetchedContent): FetchedContent {
        val repo = GitHubUrls.repoOf(url) ?: return content
        val fetched = githubFetcher.fetch(repo, GitHubUrls.folderOf(url)) ?: return content
        return content.copy(title = fetched.title, description = null, text = fetched.text, siteName = content.siteName ?: "GitHub")
    }

    /** GitHub のコードのファイルの中身に、置き場所(リポジトリとパス)の見出しを付ける。 */
    internal fun withGitHubFile(file: GitHubFileRef, content: FetchedContent): FetchedContent {
        require(content.text.isNotBlank()) { "empty file" }
        return content.copy(
            title = file.fileName,
            text = "リポジトリ: ${file.repo.fullName}\nファイル: ${file.path}\n\n" + content.text,
            siteName = "GitHub"
        )
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

    private fun fetchBlocking(url: String, downloadSink: DownloadSink?, progress: StageProgressReporter?): FetchedContent {
        require(url.startsWith("http://") || url.startsWith("https://")) { "unsupported scheme: $url" }

        var current = url.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) {
            when (val step = fetchOnce(current, allowFile = downloadSink != null)) {
                is FetchStep.Done -> return step.content
                is FetchStep.Redirect -> current = step.next
                is FetchStep.File -> return download(step.url, downloadSink!!, progress)
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
    private fun download(url: HttpUrl, sink: DownloadSink, progress: StageProgressReporter?): FetchedContent {
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
            // 大きさが分かれば、読んだ量を進み具合として報告する
            val stream = if (progress != null && length > 0) {
                ProgressInputStream(body.byteStream(), length) { progress.report(it) }
            } else {
                body.byteStream()
            }
            val stored = sink.save(stream, mimeType!!, fileName, MAX_DOWNLOAD_BYTES)
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
                // テキスト(ソースコード・Markdown・プレーンテキスト)は、そのまま本文にする
                if (mediaType.type == "text") return FetchStep.Done(textFile(url, mediaType, body.byteStream()))
                throw IOException("unsupported content type: $mediaType")
            }

            val bytes = readCapped(body.byteStream())
            val document = Jsoup.parse(
                bytes.inputStream(),
                mediaType?.charset()?.name(),
                response.request.url.toString()
            )
            return FetchStep.Done(HtmlContentExtractor.extract(document).copy(links = PageLinks.collect(document)))
        }
    }

    /**
     * テキストのファイル(HTML 以外の text 系の MIME タイプ)を本文にする。タイトルは URL の最後の部分(ファイル名)。
     * 文字コードは Content-Type の指定を優先し、無ければ UTF-8・Shift_JIS の順に試す。
     */
    private fun textFile(url: HttpUrl, mediaType: MediaType, input: InputStream): FetchedContent {
        val bytes = readCapped(input)
        val decoded = mediaType.charset()?.let { String(bytes, it) } ?: TextFileReader.decode(bytes, truncatedInput = bytes.size >= MAX_BODY_BYTES)
        return FetchedContent(
            title = url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) },
            description = null,
            text = decoded.trim().take(ExtractedContent.MAX_TEXT_LENGTH)
        )
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

/** 読んだ量を [total] に対する割合で [onProgress] に知らせる。 */
internal class ProgressInputStream(
    input: InputStream,
    private val total: Long,
    private val onProgress: (Float) -> Unit
) : FilterInputStream(input) {
    private var read = 0L

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    private fun advance(bytes: Long) {
        read += bytes
        onProgress((read.toDouble() / total).toFloat().coerceAtMost(1f))
    }
}

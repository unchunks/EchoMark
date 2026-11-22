package com.unchunks.echomark.data.video

import com.unchunks.components.web.PublicAddressInterceptor
import com.unchunks.components.web.PublicOnlyDns
import com.unchunks.components.web.isAllowedRedirect
import com.unchunks.components.web.isPublicAddress
import com.unchunks.echomark.data.remote.PeerTubeFetcher
import com.unchunks.echomark.data.remote.UrlContentFetcher
import com.unchunks.echomark.domain.video.PeerTubeUrls
import com.unchunks.echomark.domain.video.VideoDownloadFailure
import com.unchunks.echomark.domain.video.VideoFileSourceKind
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** ダウンロードできなかった。[reason] は画面に出す理由(通信の失敗は [VideoDownloadFailure.NETWORK]) */
class VideoDownloadException(val reason: VideoDownloadFailure, message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * ダウンロードが認められた動画のファイルを落とす(`docs/video-offline.md`)。
 * - 直接のリンク: リンク先の Content-Type が video/… のときだけ
 * - PeerTube: 公開の API で、投稿者がダウンロードを許した(`downloadEnabled`)動画の、許されたファイル(`fileDownloadUrl`)だけ
 *
 * 接続は本文の取得と同じ決まり(共通の client から作る。ローカルネットワークに接続しない・オフラインモードで断る・
 * リダイレクトは自前で追い、https → http の格下げを拒否する)。途中まで落としたファイルがあれば、Range で続きから落とす。
 */
@Singleton
class VideoFileDownloader internal constructor(
    okHttpClient: OkHttpClient,
    /** 接続してよいアドレスか(テストではローカルのサーバーを許可する) */
    isAllowedAddress: (InetAddress) -> Boolean
) {
    @Inject
    constructor(okHttpClient: OkHttpClient) : this(okHttpClient, ::isPublicAddress)

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .dns(PublicOnlyDns(okHttpClient.dns, isAllowedAddress))
        .addNetworkInterceptor(PublicAddressInterceptor(isAllowedAddress))
        .followRedirects(false)
        .followSslRedirects(false)
        // 大きなファイルは全体の時間では区切らない(止まったままの接続は、読み込みの時間制限で切る)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val peerTube = PeerTubeFetcher(client, USER_AGENT)

    /** 落とすファイル。[label] は画質などの表示用の名前 */
    data class Source(val url: String, val label: String?)

    /** 落とし終えたファイル */
    data class Downloaded(val mimeType: String, val size: Long)

    /** ブックマークの URL([pageUrl])から、落としてよいファイルを決める。認められていなければ [VideoDownloadException] */
    fun resolve(pageUrl: String, kind: VideoFileSourceKind): Source = when (kind) {
        VideoFileSourceKind.DIRECT_FILE -> Source(pageUrl, label = null)
        VideoFileSourceKind.PEERTUBE -> {
            val ref = PeerTubeUrls.videoOf(pageUrl)
                ?: throw VideoDownloadException(VideoDownloadFailure.NOT_TARGET, "not a PeerTube video")
            val details = peerTube.fetch(ref, withTranscript = false)
                ?: throw VideoDownloadException(VideoDownloadFailure.NETWORK, "PeerTube API unavailable")
            if (!details.downloadEnabled) {
                throw VideoDownloadException(VideoDownloadFailure.NOT_ALLOWED, "download disabled by the owner")
            }
            val file = chooseFile(details.files)
                ?: throw VideoDownloadException(VideoDownloadFailure.NOT_ALLOWED, "no downloadable file")
            Source(file.url, file.label)
        }
    }

    /**
     * [source] を [part] に落とす。[part] に途中まであれば続きから(サーバーが続きを返さなければ始めから)。
     * - 合計が [maxBytes] を超える → [VideoDownloadFailure.OVER_LIMIT](分かった時点で止める)
     * - 空き容量([freeBytes])が残りの分と余裕に足りない → [VideoDownloadFailure.NO_SPACE]
     * - 動画のファイルでない → [VideoDownloadFailure.NOT_VIDEO]
     * - [isStopped] が true になったら止める(途中のファイルは残し、続きから落とせるようにする)
     * [onProgress] には、落とした量と合計(分からなければ null)を知らせる。
     */
    fun download(
        source: Source,
        part: File,
        maxBytes: Long,
        freeBytes: () -> Long,
        isStopped: () -> Boolean,
        onProgress: (downloaded: Long, total: Long?) -> Unit
    ): Downloaded {
        var url = source.url.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) {
            val existing = if (part.isFile) part.length() else 0L
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { url.resolve(it) } ?: throw IOException("invalid redirect")
                    if (!isAllowedRedirect(url, next)) throw IOException("redirect not allowed: ${url.scheme} -> ${next.scheme}")
                    url = next
                    return@use
                }
                if (response.code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0) {
                    // 途中のファイルが合わない(サーバーのファイルが変わったなど)。始めから落とし直す
                    part.delete()
                    throw IOException("range not satisfiable")
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val mimeType = UrlContentFetcher.fileMimeType(body.contentType(), url)
                if (mimeType?.startsWith("video/") != true) {
                    throw VideoDownloadException(VideoDownloadFailure.NOT_VIDEO, "not a video: ${body.contentType()}")
                }
                val range = contentRange(response.header("Content-Range"))
                val resuming = existing > 0 && response.code == HTTP_PARTIAL_CONTENT && range?.first == existing
                val start = if (resuming) existing else 0L
                val length = body.contentLength()
                val total = range?.second ?: if (length >= 0) start + length else null
                if (total != null) checkSpace(total, start, maxBytes, freeBytes)

                var written = start
                var lastReported = -1L
                FileOutputStream(part, resuming).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            if (isStopped()) throw CancellationException("download stopped")
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            if (written > maxBytes) throw VideoDownloadException(VideoDownloadFailure.OVER_LIMIT, "over limit")
                            if (written - lastReported >= REPORT_EVERY_BYTES) {
                                lastReported = written
                                onProgress(written, total)
                                // 大きさの分からないファイルは、書きながら空き容量を確かめる
                                if (total == null && freeBytes() < STORAGE_MARGIN_BYTES) {
                                    throw VideoDownloadException(VideoDownloadFailure.NO_SPACE, "storage is low")
                                }
                            }
                        }
                    }
                    out.fd.sync()
                }
                if (total != null && written < total) throw IOException("incomplete: $written / $total")
                if (written == 0L) throw VideoDownloadException(VideoDownloadFailure.NOT_VIDEO, "empty file")
                onProgress(written, total ?: written)
                return Downloaded(mimeType, written)
            }
        }
        throw IOException("too many redirects")
    }

    private fun checkSpace(total: Long, start: Long, maxBytes: Long, freeBytes: () -> Long) {
        if (total > maxBytes) throw VideoDownloadException(VideoDownloadFailure.OVER_LIMIT, "over limit: $total > $maxBytes")
        if (freeBytes() < total - start + STORAGE_MARGIN_BYTES) {
            throw VideoDownloadException(VideoDownloadFailure.NO_SPACE, "not enough space for ${total - start} bytes")
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) EchoMark/1.0"
        private const val MAX_REDIRECTS = 5
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val BUFFER_BYTES = 64 * 1024
        private const val REPORT_EVERY_BYTES = 512L * 1024
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** 落としたあとも端末に残しておく空き容量 */
        const val STORAGE_MARGIN_BYTES: Long = 200L * 1024 * 1024

        /** 選ぶ画質の上限(端末で見るには十分で、大きくなりすぎない) */
        private const val PREFERRED_MAX_HEIGHT = 720

        /** PeerTube のファイルから1つ選ぶ。720p 以下でいちばん高い画質、無ければいちばん低い画質 */
        internal fun chooseFile(files: List<PeerTubeFetcher.File>): PeerTubeFetcher.File? =
            files.filter { it.height <= PREFERRED_MAX_HEIGHT }.maxByOrNull { it.height }
                ?: files.minByOrNull { it.height }

        /** `Content-Range: bytes 100-999/1000` の始まり(100)と合計(1000。`*` なら null) */
        internal fun contentRange(header: String?): Pair<Long, Long?>? {
            val match = CONTENT_RANGE.matchEntire(header?.trim() ?: return null) ?: return null
            return match.groupValues[1].toLong() to match.groupValues[3].toLongOrNull()
        }

        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
    }
}

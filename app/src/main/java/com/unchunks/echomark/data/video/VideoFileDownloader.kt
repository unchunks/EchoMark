package com.unchunks.echomark.data.video

import com.unchunks.components.web.DownloadRejectedException
import com.unchunks.components.web.DownloadRejection
import com.unchunks.components.web.PublicAddressInterceptor
import com.unchunks.components.web.PublicOnlyDns
import com.unchunks.components.web.ResumableDownloader
import com.unchunks.components.web.isPublicAddress
import com.unchunks.echomark.data.remote.PeerTubeFetcher
import com.unchunks.echomark.data.remote.UrlContentFetcher
import com.unchunks.echomark.domain.video.PeerTubeUrls
import com.unchunks.echomark.domain.video.VideoDownloadFailure
import com.unchunks.echomark.domain.video.VideoFileSourceKind
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.InetAddress
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
 * 落とす仕組みは部品の web-content([ResumableDownloader])にある。接続は本文の取得と同じ決まり(共通の client から作る。
 * ローカルネットワークに接続しない・オフラインモードで断る・リダイレクトは自前で追い、https → http の格下げを拒否する)。
 * 途中まで落としたファイルがあれば、Range で続きから落とす。
 */
@Singleton
class VideoFileDownloader internal constructor(
    okHttpClient: OkHttpClient,
    /** 接続してよいアドレスか(テストではローカルのサーバーを許可する) */
    isAllowedAddress: (InetAddress) -> Boolean
) {
    @Inject
    constructor(okHttpClient: OkHttpClient) : this(okHttpClient, ::isPublicAddress)

    /** PeerTube の API を読む client(ファイルを落とす client と同じ決まり) */
    private val apiClient: OkHttpClient = okHttpClient.newBuilder()
        .dns(PublicOnlyDns(okHttpClient.dns, isAllowedAddress))
        .addNetworkInterceptor(PublicAddressInterceptor(isAllowedAddress))
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val peerTube = PeerTubeFetcher(apiClient, USER_AGENT)

    private val downloader = ResumableDownloader(
        okHttpClient,
        userAgent = USER_AGENT,
        isAllowedAddress = isAllowedAddress,
        storageMarginBytes = STORAGE_MARGIN_BYTES,
        readTimeoutSeconds = READ_TIMEOUT_SECONDS
    )

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
    ): Downloaded = try {
        val done = downloader.download(
            url = source.url,
            part = part,
            maxBytes = maxBytes,
            freeBytes = freeBytes,
            isStopped = isStopped,
            typeOf = { contentType, url -> UrlContentFetcher.fileMimeType(contentType, url)?.takeIf { it.startsWith("video/") } },
            onProgress = onProgress
        )
        Downloaded(done.mimeType, done.size)
    } catch (e: DownloadRejectedException) {
        val reason = when (e.rejection) {
            DownloadRejection.OVER_LIMIT -> VideoDownloadFailure.OVER_LIMIT
            DownloadRejection.NO_SPACE -> VideoDownloadFailure.NO_SPACE
            DownloadRejection.REJECTED_TYPE -> VideoDownloadFailure.NOT_VIDEO
        }
        throw VideoDownloadException(reason, e.message ?: reason.name, e)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) EchoMark/1.0"
        private const val READ_TIMEOUT_SECONDS = 30L

        /** 落としたあとも端末に残しておく空き容量 */
        const val STORAGE_MARGIN_BYTES: Long = ResumableDownloader.DEFAULT_STORAGE_MARGIN_BYTES

        /** 選ぶ画質の上限(端末で見るには十分で、大きくなりすぎない) */
        private const val PREFERRED_MAX_HEIGHT = 720

        /** PeerTube のファイルから1つ選ぶ。720p 以下でいちばん高い画質、無ければいちばん低い画質 */
        internal fun chooseFile(files: List<PeerTubeFetcher.File>): PeerTubeFetcher.File? =
            files.filter { it.height <= PREFERRED_MAX_HEIGHT }.maxByOrNull { it.height }
                ?: files.minByOrNull { it.height }
    }
}

package com.unchunks.echomark.domain.video

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.normalizeMimeType
import kotlinx.coroutines.flow.Flow
import java.net.URI

/**
 * 端末に保存した動画のファイル。
 * @property path 保存先(filesDir からの相対パス。`offline_videos/…`)
 * @property mimeType 中身の MIME タイプ(video/…)
 * @property size 大きさ(バイト)
 * @property sourceUrl ダウンロードした URL
 * @property label 画質などの表示用の名前(例: 720p)。無ければ null
 * @property savedAt 保存した日時
 */
data class SavedVideoFile(
    val path: String,
    val mimeType: String,
    val size: Long,
    val sourceUrl: String,
    val label: String?,
    val savedAt: Long
)

/** 容量の管理の画面に出す、保存した動画1件 */
data class SavedVideoListItem(
    val bookmarkId: Long,
    val title: String,
    /** 保存先(filesDir からの相対パス) */
    val path: String,
    val size: Long,
    val label: String?,
    val savedAt: Long
)

/**
 * 動画のファイルをどこから取るか。どちらも、ダウンロードが認められているものだけ(`docs/video-offline.md`)。
 * YouTube などの動画サイトのページはどちらにも当たらない(映像・音声は保存しない)。
 */
enum class VideoFileSourceKind {
    /** 動画のファイルへの直接のリンク(mp4・webm など)。ダウンロードするときに Content-Type が video/… かを確かめる */
    DIRECT_FILE,

    /** PeerTube の動画。投稿者がダウンロードを許した(downloadEnabled)ものだけを、ダウンロードするときに確かめる */
    PEERTUBE
}

/** このブックマークの動画のファイルを保存できるなら、その取り方。できなければ null */
fun Bookmark.videoFileSourceKind(): VideoFileSourceKind? {
    if (!isOfflineVideoTarget()) return null
    val url = contentUri ?: return null
    return when {
        PeerTubeUrls.videoOf(url) != null -> VideoFileSourceKind.PEERTUBE
        isDirectVideoUrl(url) || normalizeMimeType(mimeType)?.startsWith("video/") == true -> VideoFileSourceKind.DIRECT_FILE
        else -> null
    }
}

/** URL のパスが動画のファイルの拡張子で終わるか(直接のリンク) */
fun isDirectVideoUrl(url: String): Boolean {
    val path = try {
        URI(url.trim()).rawPath
    } catch (_: Exception) {
        null
    } ?: return false
    val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return extension in VIDEO_FILE_EXTENSIONS
}

private val VIDEO_FILE_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "mkv", "ogv")

/** 動画のファイルのダウンロードの設定 */
data class VideoDownloadSettings(
    /** Wi-Fi など従量課金でない接続のときだけダウンロードする(既定はオン) */
    val wifiOnly: Boolean = true,
    /** 保存する動画のファイルの合計の上限(バイト) */
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
) {
    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 2L * 1024 * 1024 * 1024

        /** 選べる上限 */
        val MAX_TOTAL_BYTES_CHOICES: List<Long> = listOf(500L, 1024L, 2048L, 5120L, 10240L).map { it * 1024 * 1024 }
    }
}

/** 動画のファイルのダウンロードの作業(WorkManager)を積む・止める・見守る */
interface VideoDownloadScheduler {
    /** [bookmarkId] の動画をダウンロードし始める(動いていれば、そのまま続ける)。[wifiOnly] なら Wi-Fi のときだけ動く */
    fun enqueue(bookmarkId: Long, wifiOnly: Boolean)

    /** 止める(途中まで落としたファイルは呼び出し側で消す) */
    fun cancel(bookmarkId: Long)

    /** [bookmarkId] のダウンロードの、いちばん新しい状態。一度も積んでいない(記録が消えた)なら null */
    fun observe(bookmarkId: Long): Flow<VideoDownloadWork?>

    /** すべてのダウンロードを止める */
    fun cancelAll()
}

/** ダウンロードの作業の状態 */
sealed interface VideoDownloadWork {
    /** 順番待ち・通信できる(Wi-Fi の)ときを待っている */
    data object Waiting : VideoDownloadWork

    /** ダウンロードしている。[totalBytes] は分からなければ null */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : VideoDownloadWork

    data object Finished : VideoDownloadWork

    data class Failed(val reason: VideoDownloadFailure) : VideoDownloadWork

    data object Cancelled : VideoDownloadWork
}

/** ダウンロードできなかった理由 */
enum class VideoDownloadFailure {
    /** オフラインモードがオン */
    OFFLINE_MODE,

    /** 投稿者がダウンロードを許していない(PeerTube の downloadEnabled が false)・ダウンロードできるファイルが無い */
    NOT_ALLOWED,

    /** リンク先が動画のファイルではなかった */
    NOT_VIDEO,

    /** 保存する動画の合計の上限を超える */
    OVER_LIMIT,

    /** 端末の空き容量が足りない */
    NO_SPACE,

    /** 通信に失敗した(何度か試し直した後) */
    NETWORK,

    /** 対象ではない(ブックマークが消えた・種類が変わった) */
    NOT_TARGET
}

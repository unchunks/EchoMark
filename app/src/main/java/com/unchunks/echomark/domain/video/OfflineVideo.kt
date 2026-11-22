package com.unchunks.echomark.domain.video

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.contentKind
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 動画のブックマークを、通信できなくても見られるように保存した記録。
 * 字幕と要約はブックマークの本文・要約に入っている(DB の中なので、もともと通信せずに読める)。
 * ここには、それ以外に端末へ保存したもの(サムネイル)を持つ。
 *
 * @property thumbnailPath サムネイルの画像の保存先(filesDir からの相対パス)。保存できなかったときは null
 * @property savedAt 保存した(最後に保存し直した)日時
 */
data class OfflineVideo(
    val bookmarkId: Long,
    val thumbnailPath: String?,
    val savedAt: Long
)

/** 動画のオフライン保存の記録の読み書き。字幕の取得・サムネイルのダウンロードは [VideoOfflineScheduler] が積むワーカーが行う */
interface OfflineVideoRepository {
    fun observe(bookmarkId: Long): Flow<OfflineVideo?>

    suspend fun get(bookmarkId: Long): OfflineVideo?

    /**
     * サムネイル([thumbnailPath]。保存できなかったときは null で、前のものを残す)を結びつけて、保存した記録にする。
     * 前に保存したサムネイルと違うものなら、前のものは参照されなくなり、しばらくして掃除される。
     * ブックマークが無ければ何もせず false。
     */
    suspend fun markSaved(bookmarkId: Long, thumbnailPath: String?, savedAt: Long): Boolean

    /** 保存した記録とサムネイルを消す(ブックマークの本文の字幕・要約は残す)。無ければ何もしない */
    suspend fun remove(bookmarkId: Long)

    /** 保存先の相対パス [path] のファイル。無い・保存先でないパスなら null */
    fun fileOf(path: String?): File?
}

/** 動画のオフライン保存の作業(WorkManager)を積む・止める・見守る */
interface VideoOfflineScheduler {
    /** [bookmarkId] の保存を始める(同じブックマークの保存が動いていれば、そのまま続ける) */
    fun enqueue(bookmarkId: Long)

    fun cancel(bookmarkId: Long)

    /** [bookmarkId] の保存の作業の、いちばん新しい状態。一度も積んでいない(記録が消えた)なら null */
    fun observe(bookmarkId: Long): Flow<VideoOfflineWork?>
}

/** 保存の作業の状態 */
sealed interface VideoOfflineWork {
    data class Running(val stage: VideoOfflineStage) : VideoOfflineWork

    /** 保存した。字幕・サムネイルを保存できたか */
    data class Finished(val hasTranscript: Boolean, val hasThumbnail: Boolean) : VideoOfflineWork

    /** 保存できなかった。[offlineMode] ならオフラインモードのため */
    data class Failed(val offlineMode: Boolean) : VideoOfflineWork

    /** 利用者が止めた */
    data object Cancelled : VideoOfflineWork
}

/** 保存の作業の段 */
enum class VideoOfflineStage {
    /** 順番待ち・通信できるのを待っている */
    WAITING,

    /** 字幕を確かめている(本文に字幕が無ければ、リンク先から取り直す) */
    TRANSCRIPT,

    /** サムネイルを保存している */
    THUMBNAIL
}


/** 本文に字幕(文字起こしではなく、動画サイトの字幕)が入っているか。HtmlContentExtractor.youtubeText の見出しで見分ける */
fun hasVideoTranscript(content: String?): Boolean = content != null && TRANSCRIPT_HEADER.containsMatchIn(content)

private val TRANSCRIPT_HEADER = Regex("""(?m)^字幕(\(自動生成\))?:\r?$""")

/**
 * 動画のオフライン保存(字幕・要約・サムネイル)の対象か。動画サイトのページへの http / https のリンクで、
 * 動画のファイルそのものを保存したブックマーク(ファイルへの直接のリンク)ではないもの。
 */
fun Bookmark.isOfflineVideoTarget(): Boolean {
    if (type != BookmarkType.URL || filePath != null) return false
    val url = contentUri?.trim() ?: return false
    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return false
    return contentKind() == ContentKind.VIDEO
}

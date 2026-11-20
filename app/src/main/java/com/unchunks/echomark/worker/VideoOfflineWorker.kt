package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.network.NetworkGate
import com.unchunks.echomark.data.network.isOfflineModeBlock
import com.unchunks.echomark.data.remote.DownloadSink
import com.unchunks.echomark.data.remote.FetchedContent
import com.unchunks.echomark.data.remote.UrlContentFetcher
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.video.OfflineVideoRepository
import com.unchunks.echomark.domain.video.VideoOfflineStage
import com.unchunks.echomark.domain.video.hasVideoTranscript
import com.unchunks.echomark.domain.video.isOfflineVideoTarget
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 動画のブックマークを、通信できなくても見られるように保存する(字幕・要約・サムネイル)。
 *
 * 動画の映像・音声(ストリーム)はダウンロードしない(YouTube などの規約で認められていないため。`docs/video-offline.md`)。
 * 1. 字幕: 本文に字幕が無ければ、リンク先を取り直して字幕を入れる(YouTube・PeerTube。取れないサイトでは説明文のまま)
 * 2. サムネイル: リンク先の画像(OG 画像・PeerTube のサムネイル)を端末に保存する
 * 3. 要約: 字幕を新しく入れた・要約がまだ無いなら、要約だけを作り直す(手で直した要約は置き換えない)
 *
 * オフラインモードの間・途中でオンにされたときは、何も書かずに失敗で終える(画面で理由を出す)。
 * 字幕・サムネイルのどちらかが取れなくても、取れたものだけで保存した記録にする。
 */
@HiltWorker
class VideoOfflineWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository,
    private val offlineVideos: OfflineVideoRepository,
    private val fetcher: UrlContentFetcher,
    private val attachmentStore: AttachmentStore,
    private val gate: NetworkGate
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        val bookmark = repository.getBookmarkById(bookmarkId)
        val url = bookmark?.contentUri
        if (bookmark == null || url == null || !bookmark.isOfflineVideoTarget()) {
            Timber.w("VideoOfflineWorker: 対象ではありません id=%d", bookmarkId)
            return Result.failure(workDataOf(KEY_FAILURE to FAILURE_NOT_TARGET))
        }
        if (gate.isOffline()) return offlineMode()

        // 1. 字幕
        setProgress(workDataOf(KEY_STAGE to VideoOfflineStage.TRANSCRIPT.name))
        var imageUrl = bookmark.imageUrl
        var transcriptAdded = false
        if (!hasVideoTranscript(bookmark.content)) {
            val fetched = fetcher.fetch(url).getOrElse { e ->
                if (e.isOfflineModeBlock()) return offlineMode()
                Timber.w(e, "VideoOfflineWorker: 取得に失敗 url=%s", url)
                null
            }
            if (fetched != null) {
                imageUrl = imageUrl ?: fetched.imageUrl
                transcriptAdded = saveTranscript(bookmarkId, fetched) ?: return Result.success()
            }
        }

        // 2. サムネイル
        setProgress(workDataOf(KEY_STAGE to VideoOfflineStage.THUMBNAIL.name))
        val sink = DownloadSink { body, mimeType, fileName, maxBytes ->
            attachmentStore.saveStream(body, mimeType, fileName, maxBytes) { !isStopped }
        }
        val thumbnail = imageUrl?.let { image ->
            fetcher.downloadImage(image, sink).getOrElse { e ->
                if (e.isOfflineModeBlock()) return offlineMode()
                Timber.w(e, "VideoOfflineWorker: サムネイルを保存できない url=%s", image)
                null
            }
        }
        if (isStopped) {
            thumbnail?.let { attachmentStore.delete(it.filePath) }
            return Result.failure()
        }
        if (!offlineVideos.markSaved(bookmarkId, thumbnail?.filePath, System.currentTimeMillis())) return Result.success()

        // 3. 要約
        val latest = repository.getBookmarkById(bookmarkId) ?: return Result.success()
        if (needsSummary(latest, transcriptAdded)) repository.reprocess(bookmarkId, AnalysisScope.SUMMARY)

        return Result.success(
            workDataOf(
                KEY_HAS_TRANSCRIPT to hasVideoTranscript(latest.content),
                KEY_HAS_THUMBNAIL to (offlineVideos.get(bookmarkId)?.thumbnailPath != null)
            )
        )
    }

    /**
     * 取り直した本文に字幕があれば、ブックマークの本文に入れる。入れたら true、字幕が無ければ false。
     * ブックマークが消えていたら null。
     */
    private suspend fun saveTranscript(bookmarkId: Long, fetched: FetchedContent): Boolean? {
        // 取得の間に編集・削除されたかもしれないため、読み直してから書く
        val latest = repository.getBookmarkById(bookmarkId) ?: return null
        if (!hasVideoTranscript(fetched.text)) return false
        val url = latest.contentUri ?: return null
        repository.updateTitleAndContent(bookmarkId, UrlFetchWorker.titleAfterFetch(latest, url, fetched), contentWithTranscript(latest, fetched))
        repository.updateLinkMetadata(bookmarkId, imageUrl = latest.imageUrl ?: fetched.imageUrl, siteName = latest.siteName ?: fetched.siteName)
        return true
    }

    private fun offlineMode(): Result {
        Timber.i("VideoOfflineWorker: オフラインモードのため保存しない")
        return Result.failure(workDataOf(KEY_FAILURE to FAILURE_OFFLINE_MODE))
    }

    companion object {
        const val KEY_BOOKMARK_ID = "bookmark_id"
        const val KEY_STAGE = "stage"
        const val KEY_FAILURE = "failure"
        const val KEY_HAS_TRANSCRIPT = "has_transcript"
        const val KEY_HAS_THUMBNAIL = "has_thumbnail"
        const val FAILURE_OFFLINE_MODE = "offline_mode"
        const val FAILURE_NOT_TARGET = "not_target"

        /**
         * 字幕の入った本文。前に字幕なしで保存した本文(説明文など)があれば、その部分を字幕の入ったものに置き換え、
         * 無ければ(メモだけ・形が違う)後ろに足す([UrlFetchWorker.contentAfterFetch] と同じ)。メモは残る。
         */
        internal fun contentWithTranscript(bookmark: Bookmark, fetched: FetchedContent): String? {
            val existing = bookmark.content
            val previous = fetched.textWithoutTranscript?.takeIf { it.isNotBlank() }
            if (existing != null && previous != null && existing.contains(previous)) {
                return existing.replaceFirst(previous, fetched.text)
            }
            return UrlFetchWorker.contentAfterFetch(bookmark, fetched)
        }

        /**
         * 要約を作り直すか。手で直した要約は置き換えない。処理中・処理待ちのものは、その処理が今の本文で要約するので積まない。
         * AI の準備ができていないもの(WAITING_MODEL)は、準備ができたときに処理されるので積まない。
         */
        internal fun needsSummary(bookmark: Bookmark, transcriptAdded: Boolean): Boolean {
            if (bookmark.summaryEdited) return false
            if (bookmark.aiStatus != AiStatus.DONE && bookmark.aiStatus != AiStatus.FAILED) return false
            return transcriptAdded || bookmark.summary.isNullOrBlank()
        }
    }
}

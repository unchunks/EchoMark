package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.remote.DownloadSink
import com.unchunks.echomark.data.remote.FetchedContent
import com.unchunks.echomark.data.remote.UrlContentFetcher
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * URLブックマークのページ本文を取得して title / content に反映し、OG 画像・サイト名を保存する。
 * リンク先が PDF・画像・音声・動画なら本体をダウンロードしてファイルとして保存する(種類は URL のまま。
 * 中身は後続の [ContentExtractionWorker] が取り出す)。
 * 後続の AI 処理をブロックしないよう、失敗しても Result.success() を返す。
 */
@HiltWorker
class UrlFetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository,
    private val fetcher: UrlContentFetcher,
    private val attachmentStore: AttachmentStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        if (bookmarkId == -1L) return Result.success()

        val bookmark = repository.getBookmarkById(bookmarkId)
        val url = bookmark?.contentUri
        if (bookmark == null || url == null) {
            Timber.w("UrlFetchWorker: 対象が見つかりません id=%d", bookmarkId)
            return Result.success()
        }

        // ワークが止められたら(ブックマークの削除など)ダウンロードも止める
        val sink = DownloadSink { body, mimeType, fileName, maxBytes ->
            attachmentStore.saveStream(body, mimeType, fileName, maxBytes) { !isStopped }
        }
        val fetched = fetcher.fetch(url, sink).getOrElse { e ->
            Timber.w(e, "UrlFetchWorker: 取得に失敗 url=%s", url)
            return Result.success()
        }

        // 取得の間にタイトルやメモが編集された・削除されたかもしれないため、読み直してから書く
        // (削除されていれば、保存したファイルはどこからも参照されず、しばらくして掃除される)
        val latest = repository.getBookmarkById(bookmarkId) ?: return Result.success()

        // 前に保存したファイルは参照されなくなり、しばらくして掃除される
        fetched.file?.let { repository.updateAttachment(bookmarkId, it) }
        repository.updateTitleAndContent(bookmarkId, titleAfterFetch(latest, url, fetched), contentAfterFetch(latest, fetched))
        // 取れなかった項目は既存の値を残す(再取得で消さない)
        repository.updateLinkMetadata(
            bookmarkId,
            imageUrl = fetched.imageUrl ?: latest.imageUrl,
            siteName = fetched.siteName ?: latest.siteName
        )
        // 取得できたことを記録する(メモ付きの URL でも、取得できていなければ再処理で取得し直せるように)
        repository.markContentFetched(bookmarkId, System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        const val KEY_BOOKMARK_ID = BookmarkAiProcessingWorker.KEY_BOOKMARK_ID

        /** 取得した後のタイトル。未編集(空、または URL のまま)のときだけページのタイトルに置き換える */
        internal fun titleAfterFetch(bookmark: Bookmark, url: String, fetched: FetchedContent): String =
            if (bookmark.title.isBlank() || bookmark.title == url) fetched.title ?: bookmark.title else bookmark.title

        /** 取得した後の本文。ページの本文はメモの後ろに追記する(再実行時に同じ本文を二重に追記しない) */
        internal fun contentAfterFetch(bookmark: Bookmark, fetched: FetchedContent): String? {
            val fetchedText = listOfNotNull(fetched.description, fetched.text.takeIf { it.isNotBlank() })
                .joinToString("\n\n")
            val existing = bookmark.content
            return when {
                fetchedText.isBlank() -> existing
                existing.isNullOrBlank() -> fetchedText
                existing.contains(fetchedText) -> existing
                else -> existing + "\n\n" + fetchedText
            }
        }
    }
}

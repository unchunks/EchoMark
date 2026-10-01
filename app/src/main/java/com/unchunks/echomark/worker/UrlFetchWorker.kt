package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.data.remote.UrlContentFetcher
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * URLブックマークのページ本文を取得して title / content に反映し、OG 画像・サイト名を保存する。
 * 後続の AI 処理をブロックしないよう、失敗しても Result.success() を返す。
 */
@HiltWorker
class UrlFetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository,
    private val fetcher: UrlContentFetcher
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

        val fetched = fetcher.fetch(url).getOrElse { e ->
            Timber.w(e, "UrlFetchWorker: 取得に失敗 url=%s", url)
            return Result.success()
        }

        // タイトルは未編集(タイトル==URL または空)のときだけ置き換える
        val newTitle = if (bookmark.title.isBlank() || bookmark.title == url) {
            fetched.title ?: bookmark.title
        } else {
            bookmark.title
        }

        // 本文はメモの後ろに追記する(再実行時の二重追記を避ける)
        val fetchedText = listOfNotNull(fetched.description, fetched.text.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
        val existing = bookmark.content
        val newContent = when {
            fetchedText.isBlank() -> existing
            existing.isNullOrBlank() -> fetchedText
            existing.contains(fetchedText) -> existing
            else -> existing + "\n\n" + fetchedText
        }

        repository.updateTitleAndContent(bookmarkId, newTitle, newContent)
        // 取れなかった項目は既存の値を残す(再取得で消さない)
        repository.updateLinkMetadata(
            bookmarkId,
            imageUrl = fetched.imageUrl ?: bookmark.imageUrl,
            siteName = fetched.siteName ?: bookmark.siteName
        )
        return Result.success()
    }

    companion object {
        const val KEY_BOOKMARK_ID = BookmarkAiProcessingWorker.KEY_BOOKMARK_ID
    }
}

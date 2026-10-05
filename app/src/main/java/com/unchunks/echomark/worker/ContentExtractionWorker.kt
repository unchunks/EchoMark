package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 保存したファイル(画像・PDF・音声・動画)から、要約に使うテキストを取り出して本文に保存する。
 * 画像は OCR、PDF はテキスト抽出(スキャンなら OCR)、音声・動画は文字起こし。
 * 後続の AI 処理をブロックしないよう、失敗しても Result.success() を返す。
 *
 * TODO: 取り出し処理は未実装(いまは何もしない)
 */
@HiltWorker
class ContentExtractionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        if (bookmarkId == -1L) return Result.success()
        val bookmark = repository.getBookmarkById(bookmarkId)
        if (bookmark?.filePath == null) {
            Timber.w("ContentExtractionWorker: ファイルのあるブックマークが見つかりません id=%d", bookmarkId)
        }
        return Result.success()
    }

    companion object {
        const val KEY_BOOKMARK_ID = BookmarkAiProcessingWorker.KEY_BOOKMARK_ID
    }
}

package com.unchunks.echomark.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.unchunks.echomark.R
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.contentKind
import com.unchunks.echomark.domain.extract.ContentExtractor
import com.unchunks.echomark.domain.extract.ExtractedBody
import com.unchunks.echomark.domain.extract.ExtractedContent
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File

/**
 * 保存したファイル(画像・PDF・音声・動画・テキスト)から、要約に使うテキストを取り出して本文に保存する。
 * 画像は OCR とラベル、PDF はテキスト抽出(スキャンなら OCR)、音声・動画は文字起こし([ContentExtractor])。
 *
 * - 本文は [ExtractedBody] の形式で書く。ユーザーのメモ(区切りより前)は残し、取り出し直したときは前回の結果を置き換える
 * - タイトルがファイル名のまま(未編集)で、ファイルがタイトルを持っていれば(PDF のメタデータ)置き換える
 * - 長い音声・動画の文字起こしは数分〜数十分かかるため、フォアグラウンド(通知を出す)で実行し、
 *   WorkManager の実行時間の上限(10 分)やプロセスの停止で中断されにくくする
 * - 後続の AI 処理をブロックしないよう、失敗しても Result.success() を返す
 */
@HiltWorker
class ContentExtractionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository,
    private val extractor: ContentExtractor
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        if (bookmarkId == -1L) return Result.success()
        val bookmark = repository.getBookmarkById(bookmarkId)
        val filePath = bookmark?.filePath
        // URL のチェーンでは毎回この段を通る。リンク先が HTML(ファイルなし)なら何もしない
        if (filePath == null) return Result.success()
        val file = File(applicationContext.filesDir, filePath)
        if (!file.isFile) {
            Timber.w("ContentExtractionWorker: ファイルがありません id=%d", bookmarkId)
            return Result.success()
        }
        val kind = bookmark.contentKind()

        if (extractor.isLongRunning(file, bookmark.mimeType, kind)) startForeground()

        val extracted = try {
            extractor.extract(file, bookmark.mimeType, kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ContentExtractionWorker: 取り出しに失敗 id=%d", bookmarkId)
            null
        } ?: return Result.success()

        // 取り出しの間にメモやタイトルが編集された・削除されたかもしれないため、読み直してから書く
        val latest = repository.getBookmarkById(bookmarkId) ?: return Result.success()
        repository.updateTitleAndContent(
            bookmarkId,
            title = titleAfterExtraction(latest, extracted),
            content = ExtractedBody.merge(latest.content, extracted)
        )
        return Result.success()
    }

    /** 長い処理の間、通知を出してフォアグラウンドで実行する。始められなくても(バックグラウンドからの開始の制限など)処理は続ける。 */
    private suspend fun startForeground() {
        try {
            setForeground(createForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ContentExtractionWorker: フォアグラウンドにできません")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private fun createForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notification_channel_extraction))
                .setDescription(context.getString(R.string.notification_channel_extraction_desc))
                .build()
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.extraction_notification_title))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val KEY_BOOKMARK_ID = BookmarkAiProcessingWorker.KEY_BOOKMARK_ID

        /** 取り出し中の通知のチャンネル(長い文字起こしのときだけ使う) */
        const val CHANNEL_ID = "content_extraction"
        private const val NOTIFICATION_ID = 3001

        /**
         * 取り出した後のタイトル。タイトルが未編集(空・ファイル名のまま)で、ファイルがタイトルを持っていれば置き換える。
         */
        internal fun titleAfterExtraction(bookmark: Bookmark, extracted: ExtractedContent): String {
            val fileTitle = extracted.title ?: return bookmark.title
            val current = bookmark.title.trim()
            val fileName = bookmark.fileName?.trim()
            val untouched = current.isEmpty() ||
                (fileName != null && (current == fileName || current == fileName.substringBeforeLast('.')))
            return if (untouched) fileTitle else bookmark.title
        }
    }
}

package com.unchunks.echomark.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * ブックマークの本文取得・AI 処理のワークを登録・取り消す。
 * - 1件ごとの処理は一意名([uniqueWorkName])で登録し、同じブックマークの処理を並行させない(新しい依頼で置き換える)
 * - すべてのワークに [TAG] を付け、全データ削除でまとめて取り消せるようにする
 */
class BookmarkWorkScheduler @Inject constructor(
    private val workManager: WorkManager
) {

    /**
     * 1件の処理を登録する。[fetchContent] なら「本文取得 → AI 処理」のチェーン、そうでなければ AI 処理のみ。
     * 同じブックマークの登録済み・実行中の処理は取り消して置き換える。
     */
    fun enqueue(bookmarkId: Long, fetchContent: Boolean) {
        val aiRequest = aiRequest(bookmarkId)
        val name = uniqueWorkName(bookmarkId)
        if (fetchContent) {
            workManager
                .beginUniqueWork(name, ExistingWorkPolicy.REPLACE, fetchRequest(bookmarkId))
                .then(aiRequest)
                .enqueue()
        } else {
            workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, aiRequest)
        }
    }

    /** 1件の処理(登録済み・実行中)を取り消す。ブックマークの削除時に呼ぶ。 */
    fun cancel(bookmarkId: Long) {
        workManager.cancelUniqueWork(uniqueWorkName(bookmarkId))
    }

    /** このクラスで登録したすべての処理を取り消す。全データ削除時に呼ぶ。 */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    private fun aiRequest(bookmarkId: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BookmarkAiProcessingWorker>()
            .setInputData(workDataOf(BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

    private fun fetchRequest(bookmarkId: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UrlFetchWorker>()
            .setInputData(workDataOf(UrlFetchWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()

    companion object {
        /** ブックマークの処理に付けるタグ(全データ削除でまとめて取り消す)。 */
        const val TAG = "bookmark_processing"

        private const val BACKOFF_SECONDS = 30L

        /** 1件ごとの処理の一意名。本文取得のチェーンと AI 処理のみで共通にし、同じブックマークの処理を並行させない。 */
        fun uniqueWorkName(bookmarkId: Long): String = "process_bookmark_$bookmarkId"
    }
}

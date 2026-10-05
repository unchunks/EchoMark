package com.unchunks.echomark.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * ブックマークの本文取得・AI 処理のワークを登録・取り消す。
 * - 1件ごとの処理は一意名([uniqueWorkName])で登録し、同じブックマークの処理を並行させない(新しい依頼で置き換える)
 * - 一括の再処理は1本の列([BULK_WORK_NAME])にして1件ずつ順番に処理する(API のレート制限にかかりにくくする)
 * - クラウド API を使う設定のときは、AI 処理にネットワーク接続を条件として付ける(オフラインで試行回数を使い切らない)
 * - すべてのワークに [TAG] を付け、全データ削除でまとめて取り消せるようにする
 * - ワークにはブックマークごとのタグ([bookmarkTag])も付け、未完了の処理がどのブックマークのものか調べられるようにする
 */
class BookmarkWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val appSettings: AppSettingsRepository
) {

    /** 処理の対象。[fetchContent] なら AI 処理の前に本文を取得する。 */
    data class Target(val bookmarkId: Long, val fetchContent: Boolean)

    /**
     * 1件の処理を登録する。[fetchContent] なら「本文取得 → AI 処理」のチェーン、そうでなければ AI 処理のみ。
     * 同じブックマークの登録済み・実行中の処理は取り消して置き換える。
     */
    suspend fun enqueue(bookmarkId: Long, fetchContent: Boolean) {
        enqueueChain(
            uniqueWorkName(bookmarkId),
            ExistingWorkPolicy.REPLACE,
            requestsFor(Target(bookmarkId, fetchContent), aiConstraints())
        )
    }

    /**
     * 複数件を1件ずつ順番に処理する(一括の再処理用)。実行中・待機中の一括処理があれば、その後ろに続ける。
     * 列の途中の1件を取り消すと後続もすべて取り消されるため、1件の削除では取り消さない
     * (ワーカーが削除済みを確かめて何もせずに終える)。
     */
    suspend fun enqueueSequential(targets: List<Target>) {
        if (targets.isEmpty()) return
        val constraints = aiConstraints()
        // 1回の登録で長い列を作りすぎないよう、区切って後ろに続けていく
        targets.chunked(SEQUENTIAL_CHUNK_SIZE).forEach { chunk ->
            enqueueChain(
                BULK_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                chunk.flatMap { requestsFor(it, constraints) }
            )
        }
    }

    /** 1件の処理(登録済み・実行中)を取り消す。ブックマークの削除時に呼ぶ。 */
    fun cancel(bookmarkId: Long) {
        workManager.cancelUniqueWork(uniqueWorkName(bookmarkId))
    }

    /** このクラスで登録したすべての処理(一括の列も含む)を取り消す。全データ削除時に呼ぶ。 */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    /**
     * 未完了(待機中・前段待ち・実行中)の処理があるブックマークの ID。
     * どのブックマークの処理か分からないワーク(ブックマークごとのタグを付ける前のバージョンで登録したもの)が
     * 残っている間は判断できないため null を返す。
     * 登録と同じ WorkManager の直列のキューで読むため、この呼び出しより前に登録した処理は必ず含まれる。
     */
    suspend fun bookmarkIdsWithUnfinishedWork(): Set<Long>? {
        val infos = runInterruptible { workManager.getWorkInfosByTag(TAG).get() }
        val ids = infos.filterNot { it.state.isFinished }
            .map { info -> info.tags.firstNotNullOfOrNull { bookmarkIdOfTag(it) } }
        return if (ids.any { it == null }) null else ids.filterNotNull().toSet()
    }

    /** [requests] を順番に(前の処理が終わってから次を)実行する列として、一意名 [name] で登録する。 */
    private fun enqueueChain(name: String, policy: ExistingWorkPolicy, requests: List<OneTimeWorkRequest>) {
        var continuation = workManager.beginUniqueWork(name, policy, requests.first())
        for (request in requests.drop(1)) {
            continuation = continuation.then(request)
        }
        continuation.enqueue()
    }

    private fun requestsFor(target: Target, aiConstraints: Constraints): List<OneTimeWorkRequest> =
        listOfNotNull(
            fetchRequest(target.bookmarkId).takeIf { target.fetchContent },
            aiRequest(target.bookmarkId, aiConstraints)
        )

    /** クラウド API を使う設定ならネットワーク接続を待つ。端末内 AI はオフラインでも動かす。 */
    private suspend fun aiConstraints(): Constraints = when (appSettings.llmBackend.first()) {
        LlmBackend.API -> Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        LlmBackend.LOCAL -> Constraints.NONE
    }

    private fun aiRequest(bookmarkId: Long, constraints: Constraints): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BookmarkAiProcessingWorker>()
            .setInputData(workDataOf(BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(bookmarkTag(bookmarkId))
            .build()

    private fun fetchRequest(bookmarkId: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UrlFetchWorker>()
            .setInputData(workDataOf(UrlFetchWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .addTag(bookmarkTag(bookmarkId))
            .build()

    companion object {
        /** ブックマークの処理に付けるタグ(全データ削除でまとめて取り消す)。 */
        const val TAG = "bookmark_processing"

        /** 一括の再処理の列の一意名。 */
        const val BULK_WORK_NAME = "bookmark_processing_sequential"

        private const val BACKOFF_SECONDS = 30L
        private const val SEQUENTIAL_CHUNK_SIZE = 50

        /** 1件ごとの処理の一意名。本文取得のチェーンと AI 処理のみで共通にし、同じブックマークの処理を並行させない。 */
        fun uniqueWorkName(bookmarkId: Long): String = "process_bookmark_$bookmarkId"

        private const val BOOKMARK_TAG_PREFIX = "bookmark_id:"

        /** ブックマークごとのタグ。1件ごとの処理・一括の列のどちらのワークにも付ける。 */
        fun bookmarkTag(bookmarkId: Long): String = BOOKMARK_TAG_PREFIX + bookmarkId

        private fun bookmarkIdOfTag(tag: String): Long? =
            if (tag.startsWith(BOOKMARK_TAG_PREFIX)) tag.removePrefix(BOOKMARK_TAG_PREFIX).toLongOrNull() else null
    }
}

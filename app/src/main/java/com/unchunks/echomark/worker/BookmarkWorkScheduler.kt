package com.unchunks.echomark.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.ProcessingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * ブックマークの本文取得・中身の取り出し・AI 処理のワークを登録・取り消す。
 * 1件の処理は「本文取得([UrlFetchWorker]) → 中身の取り出し([ContentExtractionWorker]) → AI 処理」の順のチェーンで、
 * 必要な段だけを積む(ファイルのあるブックマークは取り出しから、メモは AI 処理だけ)。
 * - 1件ごとの処理は一意名([uniqueWorkName])で登録し、同じブックマークの処理を並行させない(新しい依頼で置き換える)
 * - 一括の再処理は1本の列([BULK_WORK_NAME])にして1件ずつ順番に処理する(API のレート制限にかかりにくくする)
 * - クラウド API を使う設定のときは、AI 処理にネットワーク接続を条件として付ける(オフラインで試行回数を使い切らない)
 * - ユーザーが保存・再処理を頼んだ1件の処理は、エクスペディット(優先実行)で登録し、Doze・バッテリーセーバーや
 *   ジョブの遅延で待たされにくくする。クォータを使い切っていれば通常のワークとして実行する
 *   ([OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST])。一括の再処理は急がないため通常のワークのまま
 * - 処理の設定([ProcessingSettings])に従い、充電中・従量課金でない接続を条件に付け、夜間にまとめて処理する設定なら
 *   保存したもの・一括の再処理を積まずに夜を待つ(ユーザーが 1 件を再処理したときはすぐ積む)
 * - すべてのワークに [TAG] を付け、全データ削除でまとめて取り消せるようにする
 * - ワークにはブックマークごとのタグ([bookmarkTag])も付け、未完了の処理がどのブックマークのものか調べられるようにする
 */
class BookmarkWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val appSettings: AppSettingsRepository
) {

    /**
     * 処理の対象。[fetchContent] なら AI 処理の前に本文を取得し、[extractContent] なら保存したファイルから
     * 要約に使うテキストを取り出す(両方なら取得 → 取り出しの順)。[scope] は AI 処理で作り直すもの
     * (要約だけ・タグだけの再処理。既定は全部)。
     */
    data class Target(
        val bookmarkId: Long,
        val fetchContent: Boolean,
        val extractContent: Boolean = false,
        val scope: AnalysisScope = AnalysisScope.ALL
    )

    /**
     * 1件の処理を登録する。「本文取得 → 中身の取り出し → AI 処理」のうち、[fetchContent]・[extractContent] で
     * 指定した段と AI 処理を順に実行する。同じブックマークの登録済み・実行中の処理は取り消して置き換える。
     * ユーザーの操作で始まる処理のため、チェーンのすべてのワークをエクスペディットにする(各ワークの制約は
     * エクスペディットで使えるネットワーク条件だけ。再試行([androidx.work.ListenableWorker.Result.retry])は
     * WorkManager が通常のジョブとして遅らせて実行する)。
     * [deferrable](保存したときの処理)で、夜間にまとめて処理する設定なら積まない。積んだら true。
     */
    suspend fun enqueue(
        bookmarkId: Long,
        fetchContent: Boolean,
        extractContent: Boolean = false,
        deferrable: Boolean = false,
        scope: AnalysisScope = AnalysisScope.ALL
    ): Boolean {
        val settings = appSettings.processingSettings.first()
        // 夜間にまとめて処理する設定なら、保存したものは積まずに処理待ちのまま夜を待つ(NightlyProcessingTask が積む)
        if (deferrable && settings.nightlyBatch) return false
        enqueueChain(
            uniqueWorkName(bookmarkId),
            ExistingWorkPolicy.REPLACE,
            requestsFor(Target(bookmarkId, fetchContent, extractContent, scope), policy(settings), expedited = true)
        )
        return true
    }

    /**
     * 複数件を1件ずつ順番に処理する(一括の再処理用)。実行中・待機中の一括処理があれば、その後ろに続ける。
     * 列の途中の1件を取り消すと後続もすべて取り消されるため、1件の削除では取り消さない
     * (ワーカーが削除済みを確かめて何もせずに終える)。
     * 夜間にまとめて処理する設定のときは、[nightly] でなければ積まない(処理待ちのまま夜を待つ)。
     * [nightly] の列は充電中を条件にする。積んだら true。
     */
    suspend fun enqueueSequential(targets: List<Target>, nightly: Boolean = false): Boolean {
        if (targets.isEmpty()) return false
        val settings = appSettings.processingSettings.first()
        if (settings.nightlyBatch && !nightly) return false
        val policy = policy(settings).let { if (nightly) it.copy(requiresCharging = true) else it }
        // 1回の登録で長い列を作りすぎないよう、区切って後ろに続けていく
        targets.chunked(SEQUENTIAL_CHUNK_SIZE).forEach { chunk ->
            enqueueChain(
                BULK_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                // 一括は急がない。エクスペディットのクォータを使い切って、ユーザーの操作の処理に回らなくなるのを避ける
                chunk.flatMap { requestsFor(it, policy, expedited = false) }
            )
        }
        return true
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
     * 一括の列(待機中・実行中)を取り消し、取り消した列にあったブックマークの ID を返す
     * (処理待ちのまま残らないよう、呼び出し側でやめたことにする)。
     */
    suspend fun cancelBulk(): Set<Long> {
        val ids = runInterruptible { workManager.getWorkInfosForUniqueWork(BULK_WORK_NAME).get() }
            .filterNot { it.state.isFinished }
            .mapNotNullTo(mutableSetOf()) { info -> info.tags.firstNotNullOfOrNull { bookmarkIdOfTag(it) } }
        runInterruptible { workManager.cancelUniqueWork(BULK_WORK_NAME).result.get() }
        return ids
    }

    /**
     * 一括の列の進み具合。AI 処理のワーク(1 件ごとに 1 つ)を数える。列が動いていなければ null。
     * 前に終えた列のワークが残っていても数えないよう、列が動き始めたときに終えていた数を差し引く。
     */
    fun observeBulkProgress(): Flow<BulkProgress?> {
        var baseline: Int? = null
        return workManager.getWorkInfosForUniqueWorkFlow(BULK_WORK_NAME)
            .map { infos ->
                val ai = infos.filter { info -> info.tags.contains(BookmarkAiProcessingWorker::class.java.name) }
                val finished = ai.count { it.state.isFinished }
                if (ai.all { it.state.isFinished }) {
                    baseline = null
                    null
                } else {
                    val start = baseline ?: finished.also { baseline = it }
                    BulkProgress(done = finished - start, total = ai.size - start)
                }
            }
            .distinctUntilChanged()
    }

    /** 一括の列の進み具合([done] 件を終え、全体は [total] 件) */
    data class BulkProgress(val done: Int, val total: Int)

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

    /**
     * [bookmarkId] の実行中の段の進み具合(0〜1)。段が報告していない・実行中のワークが無ければ null。
     * 段のワーカーが [KEY_STAGE_PROGRESS] に書いた値(ダウンロード・文字起こしなど)を読む。
     */
    fun observeStageProgress(bookmarkId: Long): Flow<Float?> =
        workManager.getWorkInfosByTagFlow(bookmarkTag(bookmarkId))
            .map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?.progress?.getFloat(KEY_STAGE_PROGRESS, -1f)
                    ?.takeIf { it >= 0f }
            }
            .distinctUntilChanged()

    /** [requests] を順番に(前の処理が終わってから次を)実行する列として、一意名 [name] で登録する。 */
    private fun enqueueChain(name: String, policy: ExistingWorkPolicy, requests: List<OneTimeWorkRequest>) {
        var continuation = workManager.beginUniqueWork(name, policy, requests.first())
        for (request in requests.drop(1)) {
            continuation = continuation.then(request)
        }
        continuation.enqueue()
    }

    /**
     * ワークに付ける条件。
     * @property aiUsesApi タグ付け・要約のどちらかにクラウド API を使う(AI 処理にネットワーク接続を求める)
     * @property unmeteredOnly ネットワークを使う段(本文の取得・クラウド API の AI 処理)は従量課金でない接続を待つ
     * @property requiresCharging 重い段(中身の取り出し・AI 処理)は充電中を待つ
     */
    internal data class RequestPolicy(
        val aiUsesApi: Boolean = false,
        val unmeteredOnly: Boolean = false,
        val requiresCharging: Boolean = false
    ) {
        private val networkType get() = if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val fetchConstraints: Constraints get() = Constraints.Builder().setRequiredNetworkType(networkType).build()

        val extractionConstraints: Constraints
            get() = Constraints.Builder().setRequiresCharging(requiresCharging).build()

        val aiConstraints: Constraints
            get() = Constraints.Builder()
                .setRequiredNetworkType(if (aiUsesApi) networkType else NetworkType.NOT_REQUIRED)
                .setRequiresCharging(requiresCharging)
                .build()
    }

    /** 設定から、ワークに付ける条件を決める。端末内 AI だけならオフラインでも AI 処理を動かす。 */
    private suspend fun policy(settings: ProcessingSettings): RequestPolicy {
        val tasks = appSettings.aiTaskSettings.first()
        return RequestPolicy(
            aiUsesApi = listOf(AiTask.TAGGING, AiTask.SUMMARY).any { tasks[it]?.backend == LlmBackend.API },
            unmeteredOnly = settings.unmeteredOnly,
            requiresCharging = settings.chargingOnly
        )
    }

    internal fun requestsFor(target: Target, policy: RequestPolicy, expedited: Boolean): List<OneTimeWorkRequest> =
        listOfNotNull(
            fetchRequest(target.bookmarkId, policy.fetchConstraints, expedited).takeIf { target.fetchContent },
            extractionRequest(target.bookmarkId, policy.extractionConstraints, expedited).takeIf { target.extractContent },
            aiRequest(target.bookmarkId, target.scope, policy.aiConstraints, expedited)
        )

    private fun aiRequest(
        bookmarkId: Long,
        scope: AnalysisScope,
        constraints: Constraints,
        expedited: Boolean
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BookmarkAiProcessingWorker>()
            .setInputData(
                workDataOf(
                    BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId,
                    BookmarkAiProcessingWorker.KEY_SCOPE to scope.name
                )
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(bookmarkTag(bookmarkId))
            .expeditedIf(expedited, constraints)
            .build()

    private fun fetchRequest(bookmarkId: Long, constraints: Constraints, expedited: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UrlFetchWorker>()
            .setInputData(workDataOf(UrlFetchWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setConstraints(constraints)
            .addTag(TAG)
            .addTag(bookmarkTag(bookmarkId))
            .expeditedIf(expedited, constraints)
            .build()

    /** 保存したファイルから要約用のテキストを取り出す(OCR・文字起こしなど。端末内で行うため、条件は充電中だけ) */
    private fun extractionRequest(bookmarkId: Long, constraints: Constraints, expedited: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ContentExtractionWorker>()
            .setInputData(workDataOf(ContentExtractionWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(bookmarkTag(bookmarkId))
            .expeditedIf(expedited, constraints)
            .build()

    /**
     * [expedited] ならエクスペディットにする。クォータを使い切っていても失敗にせず、通常のワークとして実行する。
     * エクスペディットで使える制約はネットワークと空き容量だけ(充電中・バッテリー残量・アイドル・コンテンツ URI を付けると
     * build() が IllegalArgumentException)で、初回の遅延も付けられない。充電中を待つワークは通常のワークにする
     * (急ぐよりも、充電中だけ動かす設定を守る)。
     */
    private fun OneTimeWorkRequest.Builder.expeditedIf(
        expedited: Boolean,
        constraints: Constraints
    ): OneTimeWorkRequest.Builder =
        if (expedited && !constraints.requiresCharging()) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) else this

    companion object {
        /** ブックマークの処理に付けるタグ(全データ削除でまとめて取り消す)。 */
        const val TAG = "bookmark_processing"

        /** 一括の再処理の列の一意名。 */
        const val BULK_WORK_NAME = "bookmark_processing_sequential"

        /** ワークの進み具合(WorkInfo.progress)に書く、実行中の段の進み具合(0〜1 の Float)のキー。 */
        const val KEY_STAGE_PROGRESS = "stage_progress"

        private const val BACKOFF_SECONDS = 30L
        private const val SEQUENTIAL_CHUNK_SIZE = 50

        /** 1件ごとの処理の一意名。チェーンの段の組み合わせによらず共通にし、同じブックマークの処理を並行させない。 */
        fun uniqueWorkName(bookmarkId: Long): String = "process_bookmark_$bookmarkId"

        private const val BOOKMARK_TAG_PREFIX = "bookmark_id:"

        /** ブックマークごとのタグ。1件ごとの処理・一括の列のどちらのワークにも付ける。 */
        fun bookmarkTag(bookmarkId: Long): String = BOOKMARK_TAG_PREFIX + bookmarkId

        private fun bookmarkIdOfTag(tag: String): Long? =
            if (tag.startsWith(BOOKMARK_TAG_PREFIX)) tag.removePrefix(BOOKMARK_TAG_PREFIX).toLongOrNull() else null
    }
}

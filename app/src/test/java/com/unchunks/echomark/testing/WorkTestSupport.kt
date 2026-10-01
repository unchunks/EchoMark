package com.unchunks.echomark.testing

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.unchunks.echomark.data.local.objectbox.EmbeddingEntity
import com.unchunks.echomark.data.local.objectbox.MyObjectBox
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.awaitCancellation
import java.util.UUID

/**
 * テスト用の WorkManager を初期化する(Robolectric)。
 * ワーカーは実際の処理をせず、取り消されるまで実行中のまま待つ。登録・置き換え・取り消しの状態だけを確かめる。
 * 制約(ネットワーク接続など)は満たされないため、制約付きのワークは待機中(ENQUEUED)のまま残る。
 */
fun initTestWorkManager(context: Context): WorkManager {
    val config = Configuration.Builder()
        .setMinimumLoggingLevel(Log.WARN)
        .setExecutor(SynchronousExecutor())
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = PendingWorker(appContext, workerParameters)
        })
        .build()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    return WorkManager.getInstance(context)
}

/** 取り消されるまで終わらないワーカー。 */
private class PendingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = awaitCancellation()
}

/** 一意名 [name] で登録されたワークの状態。 */
fun WorkManager.statesOf(name: String): List<WorkInfo.State> =
    getWorkInfosForUniqueWork(name).get().map { it.state }

/** タグ [tag] の付いたワークの状態。 */
fun WorkManager.statesByTag(tag: String): List<WorkInfo.State> =
    getWorkInfosByTag(tag).get().map { it.state }

/** JVM 上で動くメモリ内の ObjectBox(テストごとに別の識別子)。使い終わったら close する。 */
fun inMemoryBoxStore(): BoxStore = MyObjectBox.builder().inMemory("test-${UUID.randomUUID()}").build()

fun BoxStore.embeddingBox(): Box<EmbeddingEntity> = boxFor(EmbeddingEntity::class.java)

/** 固定のベクトルを返す埋め込みの Fake(次元は ObjectBox の索引に合わせる)。 */
class FakeEmbeddingProvider(override val modelVersion: String = "test-embedding-v1") : EmbeddingProvider {
    override val dimensions: Int = 768
    override suspend fun embedDocument(text: String): FloatArray = unitVector()
    override suspend fun embedQuery(text: String): FloatArray = unitVector()
    private fun unitVector() = FloatArray(dimensions) { if (it == 0) 1f else 0f }
}

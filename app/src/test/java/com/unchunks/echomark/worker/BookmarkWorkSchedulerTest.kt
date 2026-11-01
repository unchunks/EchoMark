package com.unchunks.echomark.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.assertSequential
import com.unchunks.echomark.testing.expeditedSettingOf
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import com.unchunks.echomark.testing.statesByTag
import com.unchunks.echomark.testing.statesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkWorkSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val settings = FakeAppSettingsRepository(backend = LlmBackend.LOCAL)
    private lateinit var scheduler: BookmarkWorkScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = initTestWorkManager(context)
        scheduler = BookmarkWorkScheduler(workManager, settings)
    }

    @After
    fun tearDown() = tearDownTestWorkManager(workManager)

    private fun unfinished(id: Long) =
        workManager.statesOf(BookmarkWorkScheduler.uniqueWorkName(id)).filterNot { it.isFinished }

    private fun aiWorkNetworkType(id: Long): NetworkType =
        workManager.getWorkInfosForUniqueWork(BookmarkWorkScheduler.uniqueWorkName(id)).get()
            .single { it.tags.contains(BookmarkAiProcessingWorker::class.java.name) }
            .constraints.requiredNetworkType

    private fun expeditedSettings(uniqueName: String) =
        workManager.getWorkInfosForUniqueWork(uniqueName).get().map { expeditedSettingOf(context, it) }

    @Test
    fun 同じブックマークの処理は新しい依頼で置き換わる() = runBlocking {
        scheduler.enqueue(1L, fetchContent = true)
        scheduler.enqueue(1L, fetchContent = false)

        assertEquals(1, unfinished(1L).size)
    }

    @Test
    fun 取り消しは対象のブックマークだけ() = runBlocking {
        scheduler.enqueue(1L, fetchContent = true)
        scheduler.enqueue(2L, fetchContent = true)

        scheduler.cancel(1L)

        assertTrue(unfinished(1L).isEmpty())
        assertEquals(2, unfinished(2L).size)
    }

    @Test
    fun すべて取り消す() = runBlocking {
        scheduler.enqueue(1L, fetchContent = true)
        scheduler.enqueue(2L, fetchContent = false)
        scheduler.enqueueSequential(listOf(BookmarkWorkScheduler.Target(3L, fetchContent = false)))

        scheduler.cancelAll()

        val states = workManager.statesByTag(BookmarkWorkScheduler.TAG)
        assertEquals(4, states.size)
        assertTrue(states.toString(), states.all { it == WorkInfo.State.CANCELLED })
    }

    @Test
    fun 未完了の処理があるブックマークのIDを返す() = runBlocking {
        scheduler.enqueue(1L, fetchContent = true)
        scheduler.enqueue(2L, fetchContent = false)
        scheduler.enqueueSequential(listOf(BookmarkWorkScheduler.Target(3L, fetchContent = false)))
        scheduler.cancel(2L)

        // 1件ごとの処理・一括の列のどちらも含み、取り消したものは含まない
        assertEquals(setOf(1L, 3L), scheduler.bookmarkIdsWithUnfinishedWork())
    }

    @Test
    fun 一括の列の進み具合は列が動いている間だけ返す() = runBlocking {
        assertNull(scheduler.observeBulkProgress().first())

        scheduler.enqueueSequential(
            listOf(1L, 2L, 3L).map { BookmarkWorkScheduler.Target(it, fetchContent = false) }
        )

        // 1件ごとに AI 処理のワークが 1 つ。まだ終えたものは無い
        assertEquals(BookmarkWorkScheduler.BulkProgress(done = 0, total = 3), scheduler.observeBulkProgress().first())

        scheduler.cancelBulk()

        assertNull(scheduler.observeBulkProgress().first())
    }

    @Test
    fun 一括の列を取り消すと列にあったブックマークのIDを返す() = runBlocking {
        scheduler.enqueue(9L, fetchContent = false)
        scheduler.enqueueSequential(
            listOf(1L, 2L).map { BookmarkWorkScheduler.Target(it, fetchContent = false) }
        )

        assertEquals(setOf(1L, 2L), scheduler.cancelBulk())

        assertTrue(workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME).all { it.isFinished })
        // 1件ごとの処理には触れない
        assertEquals(1, unfinished(9L).size)
        // 取り消した後はもう取り消すものが無い
        assertTrue(scheduler.cancelBulk().isEmpty())
    }

    @Test
    fun クラウドAPIの設定ではAI処理がネットワーク接続を待つ() = runBlocking {
        settings.setBackendForAll(LlmBackend.API)
        scheduler.enqueue(1L, fetchContent = false)

        assertEquals(NetworkType.CONNECTED, aiWorkNetworkType(1L))
    }

    @Test
    fun タグ付けだけクラウドAPIでもAI処理がネットワーク接続を待つ() = runBlocking {
        settings.setBackendForAll(LlmBackend.LOCAL)
        settings.setLlmBackend(AiTask.TAGGING, LlmBackend.API)
        scheduler.enqueue(1L, fetchContent = false)

        assertEquals(NetworkType.CONNECTED, aiWorkNetworkType(1L))
    }

    @Test
    fun 端末内AIの設定ではAI処理にネットワーク条件を付けない() = runBlocking {
        settings.setBackendForAll(LlmBackend.LOCAL)
        // チャットはブックマークの AI 処理に関係しない
        settings.setLlmBackend(AiTask.CHAT, LlmBackend.API)
        scheduler.enqueue(1L, fetchContent = false)

        assertEquals(NetworkType.NOT_REQUIRED, aiWorkNetworkType(1L))
    }

    @Test
    fun 一括の処理は1件ずつ順番に実行する() = runBlocking {
        scheduler.enqueueSequential(
            listOf(
                BookmarkWorkScheduler.Target(1L, fetchContent = false),
                BookmarkWorkScheduler.Target(2L, fetchContent = true),
                BookmarkWorkScheduler.Target(3L, fetchContent = false)
            )
        )

        // 1件目だけが動け、残り(本文取得を含む3件)は前の処理を待つ
        val states = workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME)
        assertSequential(states, expectedSize = 4)
    }

    @Test
    fun 一括の処理を重ねて登録すると前の処理の後ろに続く() = runBlocking {
        scheduler.enqueueSequential(listOf(BookmarkWorkScheduler.Target(1L, fetchContent = false)))
        scheduler.enqueueSequential(listOf(BookmarkWorkScheduler.Target(2L, fetchContent = false)))

        val states = workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME)
        assertSequential(states, expectedSize = 2)
    }

    @Test
    fun 多数の一括処理も1本の列にまとめる() = runBlocking {
        scheduler.enqueueSequential((1L..120L).map { BookmarkWorkScheduler.Target(it, fetchContent = false) })

        val states = workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME)
        assertSequential(states, expectedSize = 120)
    }

    @Test
    fun 一件の処理はチェーンのすべてのワークをエクスペディットにする() = runBlocking {
        scheduler.enqueue(1L, fetchContent = true, extractContent = true)

        val specs = expeditedSettings(BookmarkWorkScheduler.uniqueWorkName(1L))
        // 本文取得・中身の取り出し・AI 処理。枠を使い切っても通常のワークとして実行する
        assertEquals(3, specs.size)
        assertTrue(specs.toString(), specs.all { it == (true to OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) })
    }

    @Test
    fun クラウドAPIの設定でも1件の処理はエクスペディットにできる() = runBlocking {
        // エクスペディットで使える制約はネットワークだけ。ネットワーク条件付きでも build で弾かれない
        settings.setBackendForAll(LlmBackend.API)
        scheduler.enqueue(1L, fetchContent = true)

        val specs = expeditedSettings(BookmarkWorkScheduler.uniqueWorkName(1L))
        assertEquals(2, specs.size)
        assertTrue(specs.toString(), specs.all { it.first })
        assertEquals(NetworkType.CONNECTED, aiWorkNetworkType(1L))
    }

    @Test
    fun 一括の処理はエクスペディットにしない() = runBlocking {
        scheduler.enqueueSequential(
            listOf(
                BookmarkWorkScheduler.Target(1L, fetchContent = true, extractContent = true),
                BookmarkWorkScheduler.Target(2L, fetchContent = false)
            )
        )

        val specs = expeditedSettings(BookmarkWorkScheduler.BULK_WORK_NAME)
        // 1件目は取得・取り出し・AI 処理、2件目は AI 処理だけ
        assertEquals(4, specs.size)
        assertTrue(specs.toString(), specs.none { it.first })
    }

    @Test
    fun エクスペディットのワークの制約はネットワークだけ() {
        val target = BookmarkWorkScheduler.Target(1L, fetchContent = true, extractContent = true)
        val requests = scheduler.requestsFor(
            target,
            BookmarkWorkScheduler.RequestPolicy(aiUsesApi = true, unmeteredOnly = true),
            expedited = true
        )

        // 充電中・バッテリー残量・アイドル・コンテンツ URI の条件や初回の遅延があると、エクスペディットの build が失敗する
        assertEquals(3, requests.size)
        requests.forEach { request ->
            val constraints = request.workSpec.constraints
            assertTrue(!constraints.requiresCharging() && !constraints.requiresBatteryNotLow() && !constraints.requiresDeviceIdle())
            assertEquals(0L, request.workSpec.initialDelay)
            assertTrue(request.workSpec.expedited)
        }
    }

    private fun constraintsOf(uniqueName: String, worker: Class<*>): Constraints =
        workManager.getWorkInfosForUniqueWork(uniqueName).get()
            .single { it.tags.contains(worker.name) }
            .constraints

    @Test
    fun 充電中だけ処理する設定では重い段に充電中を求め_エクスペディットにしない() = runBlocking {
        settings.updateProcessingSettings { it.copy(chargingOnly = true) }

        scheduler.enqueue(1L, fetchContent = true, extractContent = true)

        val name = BookmarkWorkScheduler.uniqueWorkName(1L)
        // 本文の取得は軽いので充電を待たない
        assertEquals(false, constraintsOf(name, UrlFetchWorker::class.java).requiresCharging())
        assertTrue(constraintsOf(name, ContentExtractionWorker::class.java).requiresCharging())
        assertTrue(constraintsOf(name, BookmarkAiProcessingWorker::class.java).requiresCharging())
        // 充電中を待つワークはエクスペディットにできない(build が失敗する)ので通常のワークにする
        val expedited = workManager.getWorkInfosForUniqueWork(name).get()
            .associate { info -> info.tags.first { it.startsWith("com.unchunks") } to expeditedSettingOf(context, info).first }
        assertEquals(true, expedited[UrlFetchWorker::class.java.name])
        assertEquals(false, expedited[ContentExtractionWorker::class.java.name])
        assertEquals(false, expedited[BookmarkAiProcessingWorker::class.java.name])
    }

    @Test
    fun 従量課金でない接続だけの設定では通信する段がWiFiなどを待つ() = runBlocking {
        settings.setBackendForAll(LlmBackend.API)
        settings.updateProcessingSettings { it.copy(unmeteredOnly = true) }

        scheduler.enqueue(1L, fetchContent = true)

        val name = BookmarkWorkScheduler.uniqueWorkName(1L)
        assertEquals(NetworkType.UNMETERED, constraintsOf(name, UrlFetchWorker::class.java).requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, aiWorkNetworkType(1L))
    }

    @Test
    fun 夜間にまとめて処理する設定では保存したもの_一括の再処理を積まない() = runBlocking {
        settings.updateProcessingSettings { it.copy(nightlyBatch = true) }

        assertEquals(false, scheduler.enqueue(1L, fetchContent = false, deferrable = true))
        assertEquals(false, scheduler.enqueueSequential(listOf(BookmarkWorkScheduler.Target(2L, fetchContent = false))))

        assertTrue(unfinished(1L).isEmpty())
        assertTrue(workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME).isEmpty())
    }

    @Test
    fun 夜間にまとめて処理する設定でもユーザーが頼んだ1件はすぐ積む() = runBlocking {
        settings.updateProcessingSettings { it.copy(nightlyBatch = true) }

        assertTrue(scheduler.enqueue(1L, fetchContent = false))

        assertEquals(1, unfinished(1L).size)
    }

    @Test
    fun 夜間の列は充電中を待つ() = runBlocking {
        settings.updateProcessingSettings { it.copy(nightlyBatch = true) }

        assertTrue(
            scheduler.enqueueSequential(listOf(BookmarkWorkScheduler.Target(1L, fetchContent = false)), nightly = true)
        )

        assertTrue(constraintsOf(BookmarkWorkScheduler.BULK_WORK_NAME, BookmarkAiProcessingWorker::class.java).requiresCharging())
    }
}

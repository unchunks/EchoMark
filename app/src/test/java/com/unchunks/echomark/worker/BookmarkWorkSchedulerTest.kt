package com.unchunks.echomark.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.assertSequential
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import com.unchunks.echomark.testing.statesByTag
import com.unchunks.echomark.testing.statesOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkWorkSchedulerTest {

    private lateinit var workManager: WorkManager
    private val settings = FakeAppSettingsRepository(backend = LlmBackend.LOCAL)
    private lateinit var scheduler: BookmarkWorkScheduler

    @Before
    fun setUp() {
        workManager = initTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
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
}

package com.unchunks.echomark.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.linkcheck.LinkChecker
import com.unchunks.echomark.data.linkcheck.LinkProber
import com.unchunks.echomark.data.linkcheck.NetworkStatus
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.LinkCheckEntity
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 夜間の仕事としてリンク切れの確認を積む(充電中・Wi-Fi の設定を守る・オフなら積まない・重ねて積まない)。 */
@RunWith(AndroidJUnit4::class)
class LinkCheckNightlyTaskTest {

    private lateinit var db: AppDatabase
    private lateinit var workManager: WorkManager
    private val settings = FakeAppSettingsRepository()
    private lateinit var task: LinkCheckNightlyTask

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        workManager = initTestWorkManager(context)
        val prober = LinkProber(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined))
        val checker = LinkChecker(db.bookmarkDao(), db.linkCheckDao(), prober, object : NetworkStatus {
            override fun isOnline() = true
        })
        task = LinkCheckNightlyTask(workManager, { checker }, settings)
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
        db.close()
    }

    private fun works() = workManager.getWorkInfosForUniqueWork(LinkCheckWorker.WORK_NAME).get().filterNot { it.state.isFinished }

    private suspend fun save(url: String = "https://example.com/a"): Long =
        db.bookmarkDao().insert(
            Bookmark(type = BookmarkType.URL, title = url, contentUri = url, createdAt = 1, lastAccessedAt = 1).toEntity()
        )

    @Test
    fun 確認するリンクが無ければ何も積まない() = runBlocking {
        task.run()

        assertTrue(works().isEmpty())
    }

    @Test
    fun 設定でオフなら何も積まない() = runBlocking {
        save()
        settings.linkCheckEnabledFlow.value = false

        task.run()

        assertTrue(works().isEmpty())
    }

    @Test
    fun オフラインモードの間は何も積まない() = runBlocking {
        save()
        settings.offlineModeFlow.value = true

        task.run()

        assertTrue(works().isEmpty())
    }

    @Test
    fun 今夜確認するものが無ければ何も積まない() = runBlocking {
        val id = save()
        // たった今確認したばかり(14日は確認しない)
        db.linkCheckDao().upsert(LinkCheckEntity(bookmarkId = id, checkedAt = System.currentTimeMillis()))

        task.run()

        assertTrue(works().isEmpty())
    }

    @Test
    fun 既定では通信できればいつでも動く条件で積む() = runBlocking {
        save()

        task.run()

        val constraints = works().single().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
    }

    @Test
    fun 充電中だけとWi_Fiだけの設定を条件にする() = runBlocking {
        settings.updateProcessingSettings { it.copy(chargingOnly = true, unmeteredOnly = true) }
        save()

        task.run()

        val constraints = works().single().constraints
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresCharging())
    }

    @Test
    fun 前の夜のワークが条件を待っている間は重ねて積まない() = runBlocking {
        save()

        task.run()
        task.run()

        assertEquals(1, works().size)
    }
}

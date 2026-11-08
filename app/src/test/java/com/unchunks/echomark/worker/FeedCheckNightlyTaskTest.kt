package com.unchunks.echomark.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.dao.SubscriptionDao
import com.unchunks.echomark.data.local.entity.SubscriptionEntity
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 夜間の仕事としてフィードの確認を積む(充電中・Wi-Fi の設定を守る・重ねて積まない)。 */
@RunWith(AndroidJUnit4::class)
class FeedCheckNightlyTaskTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SubscriptionDao
    private lateinit var workManager: WorkManager
    private val settings = FakeAppSettingsRepository()
    private lateinit var task: FeedCheckNightlyTask

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.subscriptionDao()
        workManager = initTestWorkManager(context)
        task = FeedCheckNightlyTask(workManager, { dao }, settings)
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
        db.close()
    }

    private fun works() = workManager.getWorkInfosForUniqueWork(FeedCheckWorker.WORK_NAME).get().filterNot { it.state.isFinished }

    private suspend fun subscribe(enabled: Boolean = true) =
        dao.insertIgnore(SubscriptionEntity(type = "RSS", feedUrl = "https://example.com/feed", title = "t", createdAt = 1, enabled = enabled))

    @Test
    fun 購読が無ければ何も積まない() = runBlocking {
        task.run()

        assertTrue(works().isEmpty())
    }

    @Test
    fun 購読がすべてオフなら何も積まない() = runBlocking {
        subscribe(enabled = false)

        task.run()

        assertTrue(works().isEmpty())
    }

    @Test
    fun 既定では通信できればいつでも動く条件で積む() = runBlocking {
        subscribe()

        task.run()

        val constraints = works().single().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
    }

    @Test
    fun 充電中だけとWi_Fiだけの設定を条件にする() = runBlocking {
        settings.updateProcessingSettings { it.copy(chargingOnly = true, unmeteredOnly = true) }
        subscribe()

        task.run()

        val constraints = works().single().constraints
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresCharging())
    }

    @Test
    fun 前の夜のワークが条件を待っている間は重ねて積まない() = runBlocking {
        subscribe()

        task.run()
        task.run()

        assertEquals(1, works().size)
        assertTrue(works().single().state == WorkInfo.State.ENQUEUED || works().single().state == WorkInfo.State.BLOCKED)
    }

    @Test
    fun 条件は設定から決まる() {
        val charging = FeedCheckWorker.constraintsFor(ProcessingSettings(chargingOnly = true))
        assertTrue(charging.requiresCharging())
        assertEquals(NetworkType.CONNECTED, charging.requiredNetworkType)

        val wifi = FeedCheckWorker.constraintsFor(ProcessingSettings(unmeteredOnly = true))
        assertFalse(wifi.requiresCharging())
        assertEquals(NetworkType.UNMETERED, wifi.requiredNetworkType)
    }
}

package com.unchunks.echomark.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.repository.BookmarkRepositoryImpl
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeEmbeddingProvider
import com.unchunks.echomark.testing.TestDispatcherProvider
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.statesByTag
import com.unchunks.echomark.worker.BookmarkWorkScheduler
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 全データ削除・バックアップの読み込みと、ワーク(WorkManager)の整合のテスト。 */
@RunWith(AndroidJUnit4::class)
class DataManagementRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var boxStore: BoxStore
    private lateinit var workManager: WorkManager
    private lateinit var bookmarkRepository: BookmarkRepositoryImpl
    private lateinit var repository: DataManagementRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        boxStore = inMemoryBoxStore()
        workManager = initTestWorkManager(context)
        val dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined)
        val vectorSearch = VectorSearchDataSource(boxStore.embeddingBox())
        val scheduler = BookmarkWorkScheduler(workManager)
        bookmarkRepository = BookmarkRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            vectorSearch = vectorSearch,
            dispatcherProvider = dispatcherProvider,
            workScheduler = scheduler,
            embeddingProvider = FakeEmbeddingProvider()
        )
        repository = DataManagementRepositoryImpl(
            context = context,
            backupDao = db.backupDao(),
            vectorSearch = vectorSearch,
            boxStore = boxStore,
            modelManager = ModelManager(context, bookmarkRepository, dispatcherProvider),
            workManager = workManager,
            bookmarkRepository = bookmarkRepository,
            appSettings = FakeAppSettingsRepository(),
            apiKeyRepository = FakeApiKeyRepository(),
            workScheduler = scheduler,
            dispatcherProvider = dispatcherProvider
        )
    }

    @After
    fun tearDown() {
        db.close()
        boxStore.close()
    }

    @Test
    fun 全データ削除で処理待ちのワークを取り消す() = runBlocking {
        bookmarkRepository.saveUrlBookmark("https://example.com/a", null, null)
        bookmarkRepository.saveUrlBookmark("https://example.com/b", null, null)
        assertEquals(4, workManager.statesByTag(BookmarkWorkScheduler.TAG).count { !it.isFinished })

        repository.deleteAllData(resetSettings = false)

        val states = workManager.statesByTag(BookmarkWorkScheduler.TAG)
        assertTrue(states.toString(), states.all { it == WorkInfo.State.CANCELLED })
        assertTrue(bookmarkRepository.getAllBookmarkIds().isEmpty())
    }
}

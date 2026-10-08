package com.unchunks.echomark.data.backup

import com.unchunks.echomark.data.attachment.AttachmentStore
import android.content.Context
import android.net.Uri
import org.robolectric.Shadows.shadowOf
import java.io.File
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
import com.unchunks.echomark.testing.tearDownTestWorkManager
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
        val scheduler = BookmarkWorkScheduler(workManager, FakeAppSettingsRepository())
        bookmarkRepository = BookmarkRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            vectorSearch = vectorSearch,
            dispatcherProvider = dispatcherProvider,
            workScheduler = scheduler,
            embeddingProvider = FakeEmbeddingProvider(),
            attachmentStore = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
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
            attachmentStore = AttachmentStore(context, dispatcherProvider),
            dispatcherProvider = dispatcherProvider
        )
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
        db.close()
        boxStore.close()
    }

    @Test
    fun 全データ削除で処理待ちのワークを取り消す() = runBlocking {
        bookmarkRepository.saveUrlBookmark("https://example.com/a", null, null)
        bookmarkRepository.saveUrlBookmark("https://example.com/b", null, null)
        // URL 1件につき 本文取得 → 中身の取り出し → AI 処理
        assertEquals(6, workManager.statesByTag(BookmarkWorkScheduler.TAG).count { !it.isFinished })

        repository.deleteAllData(resetSettings = false)

        val states = workManager.statesByTag(BookmarkWorkScheduler.TAG)
        assertTrue(states.toString(), states.all { it == WorkInfo.State.CANCELLED })
        assertTrue(bookmarkRepository.getAllBookmarkIds().isEmpty())
    }

    @Test
    fun 全データ削除で添付ファイルもすぐに消し容量に添付ファイルを含める() = runBlocking {
        val store = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
        val stored = store.saveStream(ByteArray(300).inputStream(), "image/png", "a.png", 1024)
        bookmarkRepository.saveFileBookmark(stored, null, null)

        assertEquals(300L, repository.storageUsage().attachmentBytes)
        repository.deleteAllData(resetSettings = false)

        assertTrue(!File(context.filesDir, stored.filePath).exists())
        assertEquals(0L, repository.storageUsage().attachmentBytes)
    }

    @Test
    fun バックアップの読み込みで端末に無い添付ファイルはファイルなしにする() = runBlocking {
        val store = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
        val existing = store.saveStream(ByteArray(10).inputStream(), "image/png", "here.png", 1024)
        val json = """
            {"format":"echomark-backup","version":4,"exportedAt":1,"bookmarks":[
              {"id":1,"type":"IMAGE","title":"ある","createdAt":1,"aiStatus":"DONE",
               "filePath":"${existing.filePath}","mimeType":"image/png","fileName":"here.png","fileSize":10},
              {"id":2,"type":"PDF","title":"無い","createdAt":2,"aiStatus":"DONE",
               "filePath":"attachments/elsewhere.pdf","mimeType":"application/pdf","fileName":"doc.pdf","fileSize":99}
            ]}
        """.trimIndent()
        val uri = Uri.parse("content://test/backup.json")
        shadowOf(context.contentResolver).registerInputStream(uri, json.byteInputStream())

        repository.importBackup(uri.toString())

        val byTitle = bookmarkRepository.getBookmarksByIds(bookmarkRepository.getAllBookmarkIds()).associateBy { it.title }
        assertEquals(existing.filePath, byTitle.getValue("ある").filePath)
        assertEquals(null, byTitle.getValue("無い").filePath)
        assertEquals("doc.pdf", byTitle.getValue("無い").fileName)
    }
}

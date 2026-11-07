package com.unchunks.echomark.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkNoteEntity
import com.unchunks.echomark.data.local.entity.CollectionEntity
import com.unchunks.echomark.data.local.entity.CollectionItemEntity
import com.unchunks.echomark.data.local.entity.LinkCheckEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.repository.BookmarkRepositoryImpl
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicatePair
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.domain.model.ChatTemplate
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeEmbeddingProvider
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.statesByTag
import com.unchunks.echomark.testing.tearDownTestWorkManager
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
import org.robolectric.Shadows.shadowOf
import java.io.File

/** 全データ削除・バックアップの読み込みと、ワーク(WorkManager)の整合のテスト。 */
@RunWith(AndroidJUnit4::class)
class DataManagementRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var boxStore: BoxStore
    private lateinit var workManager: WorkManager
    private lateinit var bookmarkRepository: BookmarkRepositoryImpl
    private lateinit var repository: DataManagementRepositoryImpl
    private val settings = FakeAppSettingsRepository()

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
            noteDao = db.bookmarkNoteDao(),
            collectionDao = db.collectionDao(),
            reviewCardDao = db.reviewCardDao(),
            categoryDao = db.categoryDao(),
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
            appSettings = settings,
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
    fun 全データ削除は設定を残す場合でも検索の履歴を消す() = runBlocking {
        settings.searchHistoryFlow.value = listOf("東京 ラーメン", "kotlin")

        repository.deleteAllData(resetSettings = false)

        assertEquals(emptyList<String>(), settings.searchHistoryFlow.value)
    }

    @Test
    fun 全データ削除は設定を残す場合でも自分で作ったチャットのテンプレートを消す() = runBlocking {
        settings.chatTemplatesFlow.value = listOf(ChatTemplate("1", "会議", "会議の議題をまとめて"))

        repository.deleteAllData(resetSettings = false)

        assertEquals(emptyList<ChatTemplate>(), settings.chatTemplatesFlow.value)
    }

    @Test
    fun 全データ削除はコレクションも消す_設定を残す場合も() = runBlocking {
        val id = bookmarkRepository.saveUrlBookmark("https://example.com/a", null, null).id
        val collectionId = db.collectionDao().insert(CollectionEntity(name = "集め物", createdAt = 1))
        db.collectionDao().insertItem(CollectionItemEntity(collectionId, id, addedAt = 2))
        // ブックマークの無い空のコレクションも消える
        db.collectionDao().insert(CollectionEntity(name = "空", createdAt = 3))

        repository.deleteAllData(resetSettings = false)

        assertEquals(0, db.collectionDao().getAll().size)
        assertEquals(0, db.collectionDao().getAllItems().size)
    }

    @Test
    fun コレクションはバックアップに書き出され_読み込むと名前で付き_同じ名前には足される() = runBlocking {
        val id = bookmarkRepository.saveUrlBookmark("https://example.com/a", null, null).id
        val collectionId = db.collectionDao().insert(CollectionEntity(name = "集め物", description = "説明", createdAt = 5))
        db.collectionDao().insertItem(CollectionItemEntity(collectionId, id, addedAt = 6))
        val text = com.unchunks.echomark.data.backup.BackupJson.encode(db.backupDao().snapshot(exportedAt = 1))

        repository.deleteAllData(resetSettings = false)
        // 読み込む先に、同じ名前のコレクションが既にある
        val existing = db.collectionDao().insert(CollectionEntity(name = "集め物", description = "既存の説明", createdAt = 1))
        db.backupDao().merge(com.unchunks.echomark.data.backup.BackupJson.decode(text).data)

        val collections = db.collectionDao().getAll()
        assertEquals(listOf(existing), collections.map { it.id })
        assertEquals("既存の説明", collections.single().description)
        val newBookmarkId = bookmarkRepository.getAllBookmarkIds().single()
        assertEquals(listOf(existing to newBookmarkId), db.collectionDao().getAllItems().map { it.collectionId to it.bookmarkId })
    }

    @Test
    fun 全データ削除はリンク切れの確認の状態も消す_設定を残す場合も() = runBlocking {
        val id = bookmarkRepository.saveUrlBookmark("https://example.com/a", null, null).id
        db.linkCheckDao().upsert(LinkCheckEntity(bookmarkId = id, checkedAt = 5, muted = true))
        settings.linkCheckEnabledFlow.value = false

        repository.deleteAllData(resetSettings = false)

        assertEquals(0, db.linkCheckDao().getAll().size)
        // 夜の確認のオンオフは設定なので残る
        assertEquals(false, settings.linkCheckEnabledFlow.value)
    }

    @Test
    fun 全データ削除はノートも消す() = runBlocking {
        val id = bookmarkRepository.saveUrlBookmark("https://example.com/a", null, null).id
        db.bookmarkNoteDao().insert(BookmarkNoteEntity(bookmarkId = id, text = "ノート", createdAt = 1, updatedAt = 1))

        repository.deleteAllData(resetSettings = false)

        assertEquals(0, db.backupDao().getAllNotes().size)
    }

    @Test
    fun ノートはバックアップに書き出され_読み込むと付く() = runBlocking {
        val id = bookmarkRepository.saveUrlBookmark("https://example.com/a", null, null).id
        db.bookmarkNoteDao().insert(BookmarkNoteEntity(bookmarkId = id, text = "残すノート", quote = "引用", createdAt = 4, updatedAt = 5))
        val exported = db.backupDao().snapshot(exportedAt = 1)
        val text = com.unchunks.echomark.data.backup.BackupJson.encode(exported)

        repository.deleteAllData(resetSettings = false)
        val decoded = com.unchunks.echomark.data.backup.BackupJson.decode(text).data
        val result = db.backupDao().merge(decoded)

        assertEquals(1, result.notesAdded)
        val newId = bookmarkRepository.getAllBookmarkIds().single()
        val note = db.bookmarkNoteDao().getByBookmark(newId).single()
        assertEquals("残すノート", note.text)
        assertEquals("引用", note.quote)
        assertEquals(5L, note.updatedAt)
    }

    @Test
    fun 全データ削除は設定を残す場合でも両方残すと答えた重複の組を消す() = runBlocking {
        settings.dismissedDuplicatesFlow.value = setOf(DuplicatePair.of(1, 2))
        settings.duplicateSensitivityFlow.value = DuplicateSensitivity.LOOSE

        repository.deleteAllData(resetSettings = false)

        assertEquals(emptySet<DuplicatePair>(), settings.dismissedDuplicatesFlow.value)
        // 見分け方の設定は設定なので残る
        assertEquals(DuplicateSensitivity.LOOSE, settings.duplicateSensitivityFlow.value)
    }

    @Test
    fun 全データ削除で設定も初期化するときは両方残すと答えた組も見分け方も戻る() = runBlocking {
        settings.dismissedDuplicatesFlow.value = setOf(DuplicatePair.of(1, 2))
        settings.duplicateSensitivityFlow.value = DuplicateSensitivity.LOOSE

        repository.deleteAllData(resetSettings = true)

        assertEquals(emptySet<DuplicatePair>(), settings.dismissedDuplicatesFlow.value)
        assertEquals(DuplicateSensitivity.DEFAULT, settings.duplicateSensitivityFlow.value)
    }

    @Test
    fun 全データ削除で設定も初期化するときも検索の履歴は消える() = runBlocking {
        settings.searchHistoryFlow.value = listOf("kotlin")

        repository.deleteAllData(resetSettings = true)

        assertEquals(emptyList<String>(), settings.searchHistoryFlow.value)
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

    @Test
    fun 全データ削除は設定を残す場合でもブックマークの場所を消す() = runBlocking {
        val id = bookmarkRepository.saveUrlBookmark("https://www.google.com/maps/@35.6812,139.7671,15z", null, null).id
        db.bookmarkDao().updateLocation(id, 35.0, 139.0, "自宅の近く")
        assertTrue(bookmarkRepository.getBookmarkById(id)!!.hasPlace)

        repository.deleteAllData(resetSettings = false)

        // ブックマークの行ごと消える(場所は列なので、一緒に消える)。同じ URL を保存し直しても、前の場所は残っていない
        assertTrue(bookmarkRepository.getAllBookmarkIds().isEmpty())
        val again = bookmarkRepository.saveUrlBookmark("https://example.com/plain", null, null).id
        assertEquals(null, bookmarkRepository.getBookmarkById(again)!!.placeName)
        assertEquals(null, bookmarkRepository.getBookmarkById(again)!!.latitude)
    }

    private val store by lazy { AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined)) }

    /** 画像のブックマーク(ファイルつき)に、オフラインコピーと要約の履歴を付け、チャットのテンプレートも作る */
    private suspend fun seedWithFiles(): Long {
        val image = store.saveStream("画像の中身".byteInputStream(), "image/png", "photo.png", 1024)
        val id = bookmarkRepository.saveFileBookmark(image, "写真", null).id
        val mhtml = store.saveStream("ページの中身".byteInputStream(), "text/plain", "page.txt", 1024)
        val pdf = store.saveStream("PDF の中身".byteInputStream(), "application/pdf", "page.pdf", 1024)
        db.bookmarkDao().updateSnapshot(id, mhtml.filePath, pdf.filePath, 77)
        db.backupDao().insertSummaryHistory(
            com.unchunks.echomark.data.local.entity.SummaryHistoryEntity(
                bookmarkId = id, summary = "前の要約", source = "MANUAL", createdAt = 5
            )
        )
        settings.chatTemplatesFlow.value = listOf(ChatTemplate("t1", "要点", "要点をまとめて"))
        return id
    }

    private fun export(includeFiles: Boolean): ByteArray = runBlocking {
        val out = java.io.ByteArrayOutputStream()
        val uri = Uri.parse("content://test/out-$includeFiles")
        shadowOf(context.contentResolver).registerOutputStream(uri, out)
        repository.exportBackup(uri.toString(), includeFiles)
        out.toByteArray()
    }

    private suspend fun import(bytes: ByteArray, name: String = "in") =
        Uri.parse("content://test/$name").let { uri ->
            shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
            repository.importBackup(uri.toString())
        }

    @Test
    fun ファイルも含めて書き出すと_添付ファイル_オフラインコピー_要約の履歴_テンプレートまで戻る() = runBlocking {
        val id = seedWithFiles()
        val before = bookmarkRepository.getBookmarkById(id)!!
        val bytes = export(includeFiles = true)
        assertTrue(BackupArchive.isZip(bytes.copyOf(4)))

        repository.deleteAllData(resetSettings = false)
        assertEquals(0L, repository.storageUsage().attachmentBytes)
        val summary = import(bytes)

        assertEquals(1, summary.bookmarksAdded)
        assertEquals(3, summary.filesRestored)
        val restoredId = bookmarkRepository.getAllBookmarkIds().single()
        val entity = db.bookmarkDao().getById(restoredId)!!
        assertEquals(before.filePath, entity.filePath)
        assertEquals("画像の中身", store.existingFile(entity.filePath)!!.readText())
        assertEquals("ページの中身", store.existingFile(entity.snapshotPath)!!.readText())
        assertEquals("PDF の中身", store.existingFile(entity.snapshotPdfPath)!!.readText())
        assertEquals(77L, entity.snapshotAt)
        assertEquals(listOf("前の要約"), db.backupDao().getAllSummaryHistory().map { it.summary })
        assertEquals(listOf("要点"), settings.chatTemplatesFlow.value.map { it.title })
        // 展開に使った一時的な場所は残さない
        assertTrue(File(context.cacheDir, "backup-import").listFiles().isNullOrEmpty())
    }

    @Test
    fun JSONだけのときはファイルを入れず_読み込む端末に無いオフラインコピーは外す() = runBlocking {
        seedWithFiles()
        val bytes = export(includeFiles = false)
        assertTrue(!BackupArchive.isZip(bytes.copyOf(4)))

        repository.deleteAllData(resetSettings = false)
        val summary = import(bytes)

        assertEquals(0, summary.filesRestored)
        val entity = db.bookmarkDao().getById(bookmarkRepository.getAllBookmarkIds().single())!!
        assertEquals(null, entity.filePath)
        assertEquals(null, entity.snapshotPath)
        assertEquals(null, entity.snapshotPdfPath)
        assertEquals(null, entity.snapshotAt)
        // 要約の履歴とテンプレートは JSON にも入る
        assertEquals(1, db.backupDao().getAllSummaryHistory().size)
        assertEquals(1, settings.chatTemplatesFlow.value.size)
    }

    @Test
    fun 壊れたバックアップは何も読み込まず_今のデータはそのまま() = runBlocking {
        val existing = bookmarkRepository.saveUrlBookmark("https://example.com/keep", null, null).id
        // ノートが、ファイルに無いブックマークを指している
        val json = """
            {"format":"echomark-backup","version":20,"exportedAt":1,
             "bookmarks":[{"id":1,"type":"TEXT","title":"新しいメモ","createdAt":1,"aiStatus":"DONE"}],
             "notes":[{"bookmarkId":2,"text":"迷子のノート","createdAt":1,"updatedAt":1}],
             "chatTemplates":[{"id":"t9","title":"読み込まない","text":"…"}]}
        """.trimIndent()

        val error = runCatching { import(json.toByteArray()) }.exceptionOrNull()

        assertTrue(error.toString(), error is com.unchunks.echomark.domain.repository.DataOperationException)
        assertTrue(error!!.message!!.contains("今のデータはそのまま"))
        assertEquals(listOf(existing), bookmarkRepository.getAllBookmarkIds())
        assertTrue(settings.chatTemplatesFlow.value.isEmpty())
    }

    @Test
    fun 書き込みの途中で失敗したら_入れたファイルを消し_テンプレートを元に戻す() = runBlocking {
        seedWithFiles()
        val bytes = export(includeFiles = true)
        repository.deleteAllData(resetSettings = false)
        settings.chatTemplatesFlow.value = listOf(ChatTemplate("mine", "自分の", "そのまま残す"))
        // DB を閉じて、統合(最後の段)で失敗させる
        db.close()

        val error = runCatching { import(bytes) }.exceptionOrNull()

        assertTrue(error.toString(), error is com.unchunks.echomark.domain.repository.DataOperationException)
        assertEquals(0L, repository.storageUsage().attachmentBytes)
        assertEquals(listOf("mine"), settings.chatTemplatesFlow.value.map { it.id })
    }
}

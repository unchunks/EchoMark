package com.unchunks.echomark.data.ai.model

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.testing.FakeBookmarkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File
import java.security.MessageDigest

/** 取り込み元(SAF のプロバイダ)が想定外の例外を投げても、クラッシュせず失敗として扱う。 */
@RunWith(AndroidJUnit4::class)
class ModelManagerTest {

    private lateinit var context: Context
    private lateinit var manager: ModelManager
    private val bookmarks = FakeBookmarkRepository()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Robolectric.setupContentProvider(QueryFailingProvider::class.java, QUERY_FAILING)
        Robolectric.setupContentProvider(OpenFailingProvider::class.java, OPEN_FAILING)
        Robolectric.setupContentProvider(BytesProvider::class.java, BYTES)
        File(context.filesDir, "models").deleteRecursively()
        manager = ModelManager(context, bookmarks, TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @Test
    fun 情報の問い合わせで想定外の例外が出たら読み込み失敗にする() {
        manager.startImport(Uri.parse("content://$QUERY_FAILING/gemma.task"))

        assertEquals(ModelImportState.Failed(ModelImportError.ReadFailed), manager.importState.value)
        assertNull(manager.installedModel.value)
    }

    @Test
    fun 読み込みで想定外の例外が出たら読み込み失敗にして一時ファイルを消す() {
        manager.startImport(Uri.parse("content://$OPEN_FAILING/gemma.task"))

        assertEquals(ModelImportState.Failed(ModelImportError.ReadFailed), manager.importState.value)
        assertNull(manager.installedModel.value)
        assertFalse(File(context.filesDir, "models/import.part").exists())
    }

    @Test
    fun 中身が拡張子の形式でないファイルは取り込まず今のモデルを残す() {
        BytesProvider.files["model.litertlm"] = "LITERTLM-model".toByteArray()
        BytesProvider.files["broken.litertlm"] = "<!DOCTYPE html>".toByteArray()
        manager.startImport(Uri.parse("content://$BYTES/model.litertlm"))
        val installed = manager.installedModel.value!!

        manager.startImport(Uri.parse("content://$BYTES/broken.litertlm"))

        assertEquals(ModelImportState.Failed(ModelImportError.InvalidModelFile(".litertlm")), manager.importState.value)
        assertEquals(listOf(installed), manager.installed.value.models)
        assertTrue(File(context.filesDir, "models/${installed.fileName}").isFile)
        assertFalse(File(context.filesDir, "models/import.part").exists())
    }

    @Test
    fun 旧形式のtaskはZIPなら取り込む() {
        BytesProvider.files["gemma3-1b-it.task"] = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)
        manager.startImport(Uri.parse("content://$BYTES/gemma3-1b-it.task"))

        val installed = manager.installedModel.value!!
        assertTrue(installed.fileName, installed.fileName.startsWith("local_llm_") && installed.fileName.endsWith(".task"))
        assertEquals(installed.id, LocalModelInfo.idOf(installed.fileName))
        assertTrue(installed.isLegacyTaskFormat)
    }

    @Test
    fun 取り込むたびに一覧に加え最初のモデルを既定にする() {
        BytesProvider.files["a.litertlm"] = "LITERTLM-a".toByteArray()
        BytesProvider.files["b.litertlm"] = "LITERTLM-b".toByteArray()
        manager.startImport(Uri.parse("content://$BYTES/a.litertlm"))
        manager.clearImportResult()
        manager.startImport(Uri.parse("content://$BYTES/b.litertlm"))

        val installed = manager.installed.value
        assertEquals(listOf("a.litertlm", "b.litertlm"), installed.models.map { it.displayName })
        assertEquals(2, installed.models.map { it.id }.distinct().size)
        assertEquals("a.litertlm", installed.default?.displayName)
        assertEquals("a.litertlm", manager.installedModel.value?.displayName)
        // 一覧はファイルに保存し、作り直した ModelManager でも読める
        val reloaded = ModelManager(context, FakeBookmarkRepository(), TestDispatcherProvider(Dispatchers.Unconfined))
        assertEquals(installed, reloaded.installed.value)
        assertEquals(installed.models.sumOf { it.sizeBytes }, reloaded.totalBytes())
    }

    @Test
    fun 既定のモデルを変え_既定を削除したら残りの先頭を既定にする() {
        BytesProvider.files["a.litertlm"] = "LITERTLM-a".toByteArray()
        BytesProvider.files["b.litertlm"] = "LITERTLM-b".toByteArray()
        manager.startImport(Uri.parse("content://$BYTES/a.litertlm"))
        manager.startImport(Uri.parse("content://$BYTES/b.litertlm"))
        val (a, b) = manager.installed.value.models

        manager.setDefaultModel(b.id)
        assertEquals(b, manager.installedModel.value)
        // 選んだモデルが無い・null なら既定のモデル
        assertEquals(a, manager.installed.value.resolve(a.id))
        assertEquals(b, manager.installed.value.resolve(null))
        assertEquals(b, manager.installed.value.resolve("deleted"))

        manager.deleteModel(b.id)
        assertEquals(listOf(a), manager.installed.value.models)
        assertEquals(a, manager.installedModel.value)
        assertFalse(File(context.filesDir, "models/${b.fileName}").exists())

        manager.deleteModel(a.id)
        assertNull(manager.installedModel.value)
        assertNull(manager.modelFile())
    }

    @Test
    fun 以前の版の1つだけのモデルを一覧に移す() {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        File(modelsDir, "local_llm.litertlm").writeBytes("LITERTLM".toByteArray())
        File(modelsDir, "local_llm.json").writeText(
            """{"fileName":"local_llm.litertlm","displayName":"Gemma3-1B-IT.litertlm","sizeBytes":8,"importedAt":1}"""
        )

        val migrated = ModelManager(context, FakeBookmarkRepository(), TestDispatcherProvider(Dispatchers.Unconfined))

        val model = migrated.installedModel.value!!
        assertEquals("local_llm", model.id)
        assertEquals("Gemma3-1B-IT.litertlm", model.displayName)
        assertFalse(File(modelsDir, "local_llm.json").exists())
        assertTrue(File(modelsDir, "local_llm_models.json").isFile)
    }

    @Test
    fun 読み込めなかったことはそのモデルにだけ出し削除すると消す() = runBlocking {
        BytesProvider.files["a.litertlm"] = "LITERTLM-a".toByteArray()
        BytesProvider.files["b.litertlm"] = "LITERTLM-b".toByteArray()
        manager.startImport(Uri.parse("content://$BYTES/a.litertlm"))
        manager.startImport(Uri.parse("content://$BYTES/b.litertlm"))
        val (a, b) = manager.installed.value.models

        manager.reportLoadFailure(a, IllegalStateException("Failed to select jinja prompt template"))
        assertEquals(setOf(a.id), manager.loadFailures.first().keys)
        assertEquals(a, manager.loadFailures.first().getValue(a.id).model)
        manager.reportLoaded(a)
        assertTrue(manager.loadFailures.first().isEmpty())

        manager.reportLoadFailure(b, IllegalStateException("x"))
        manager.deleteModel(b.id)
        assertTrue(manager.loadFailures.first().isEmpty())
        // 一覧に無いモデルの失敗は出さない
        manager.reportLoadFailure(b, IllegalStateException("x"))
        assertTrue(manager.loadFailures.first().isEmpty())
    }

    private fun catalogEntry(content: ByteArray, sizeBytes: Long = content.size.toLong()) = ModelCatalogEntry(
        id = "test",
        name = "テストのモデル",
        repo = "litert-community/test",
        revision = "0".repeat(40),
        fileName = "test-model.litertlm",
        sizeBytes = sizeBytes,
        sha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
        license = "Apache-2.0",
        suitedFor = "テスト"
    )

    @Test
    fun ダウンロードしたモデルを確かめて一覧に加える() = runBlocking {
        val content = "LITERTLM-download".toByteArray()
        val file = File.createTempFile("download", ".litertlm").apply { writeBytes(content); deleteOnExit() }

        assertTrue(manager.install(file, catalogEntry(content)))

        val installed = manager.installedModel.value!!
        assertEquals("test-model.litertlm", installed.displayName)
        assertEquals(content.size.toLong(), installed.sizeBytes)
        assertTrue(installed.fileName.endsWith(".litertlm"))
        assertTrue(File(context.filesDir, "models/${installed.fileName}").readBytes().contentEquals(content))
        assertEquals("モデル待ちのブックマークの処理を再開する", 1, bookmarks.waitingModelEnqueues)
    }

    @Test
    fun 大きさかSHA256が合わないダウンロードは加えず一時ファイルを消す() = runBlocking {
        val content = "LITERTLM-download".toByteArray()
        val file = File.createTempFile("download", ".litertlm").apply { writeBytes(content); deleteOnExit() }

        assertFalse(manager.install(file, catalogEntry("別の中身".toByteArray(), sizeBytes = content.size.toLong())))
        assertFalse(manager.install(file, catalogEntry(content, sizeBytes = 1)))

        assertNull(manager.installedModel.value)
        assertFalse(File(context.filesDir, "models/download.part").exists())
    }

    private companion object {
        const val QUERY_FAILING = "com.unchunks.echomark.test.queryfailing"
        const val OPEN_FAILING = "com.unchunks.echomark.test.openfailing"
        const val BYTES = "com.unchunks.echomark.test.bytes"
    }
}

/** [files] に置いた中身を、パスの最後の名前で返すプロバイダ。 */
class BytesProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            val name = uri.lastPathSegment!!
            addRow(arrayOf<Any>(name, files.getValue(name).size.toLong()))
        }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File.createTempFile("bytes", ".bin").apply {
            writeBytes(files.getValue(uri.lastPathSegment!!))
            deleteOnExit()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        val files = mutableMapOf<String, ByteArray>()
    }
}

/** 問い合わせで IllegalArgumentException を投げるプロバイダ(取り込み元のアプリの不具合を再現)。 */
class QueryFailingProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        throw IllegalArgumentException("Unsupported URI: $uri")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/** 名前とサイズは返すが、開くと IllegalStateException を投げるプロバイダ。 */
class OpenFailingProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>("gemma.task", 1024L))
        }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        throw IllegalStateException("provider crashed")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

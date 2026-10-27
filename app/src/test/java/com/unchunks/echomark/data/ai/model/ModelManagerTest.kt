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

/** 取り込み元(SAF のプロバイダ)が想定外の例外を投げても、クラッシュせず失敗として扱う。 */
@RunWith(AndroidJUnit4::class)
class ModelManagerTest {

    private lateinit var context: Context
    private lateinit var manager: ModelManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Robolectric.setupContentProvider(QueryFailingProvider::class.java, QUERY_FAILING)
        Robolectric.setupContentProvider(OpenFailingProvider::class.java, OPEN_FAILING)
        Robolectric.setupContentProvider(BytesProvider::class.java, BYTES)
        File(context.filesDir, "models").deleteRecursively()
        manager = ModelManager(context, FakeBookmarkRepository(), TestDispatcherProvider(Dispatchers.Unconfined))
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
        assertEquals(installed, manager.installedModel.value)
        assertTrue(File(context.filesDir, "models/${installed.fileName}").isFile)
        assertFalse(File(context.filesDir, "models/import.part").exists())
    }

    @Test
    fun 旧形式のtaskはZIPなら取り込む() {
        BytesProvider.files["gemma3-1b-it.task"] = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)
        manager.startImport(Uri.parse("content://$BYTES/gemma3-1b-it.task"))

        val installed = manager.installedModel.value!!
        assertEquals("local_llm.task", installed.fileName)
        assertTrue(installed.isLegacyTaskFormat)
    }

    @Test
    fun 読み込めなかったことは今のモデルにだけ出し取り込み直すと消す() = runBlocking {
        BytesProvider.files["a.litertlm"] = "LITERTLM-a".toByteArray()
        BytesProvider.files["b.litertlm"] = "LITERTLM-b".toByteArray()
        manager.startImport(Uri.parse("content://$BYTES/a.litertlm"))
        val first = manager.installedModel.value!!

        manager.reportLoadFailure(first, IllegalStateException("Failed to select jinja prompt template"))
        assertEquals(first, manager.loadFailure.first()?.model)
        manager.reportLoaded(first)
        assertNull(manager.loadFailure.first())

        manager.reportLoadFailure(first, IllegalStateException("x"))
        manager.startImport(Uri.parse("content://$BYTES/b.litertlm"))
        assertNull(manager.loadFailure.first())
        // 取り込み直す前のモデルの失敗は、今のモデルには出さない
        manager.reportLoadFailure(first, IllegalStateException("x"))
        assertNull(manager.loadFailure.first())
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

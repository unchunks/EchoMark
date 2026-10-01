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
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private companion object {
        const val QUERY_FAILING = "com.unchunks.echomark.test.queryfailing"
        const val OPEN_FAILING = "com.unchunks.echomark.test.openfailing"
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

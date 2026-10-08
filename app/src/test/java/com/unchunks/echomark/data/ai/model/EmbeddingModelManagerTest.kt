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
import androidx.work.WorkManager
import com.unchunks.echomark.domain.provider.EmbeddingModelProfile
import com.unchunks.echomark.testing.TestDispatcherProvider
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.statesOf
import com.unchunks.echomark.testing.tearDownTestWorkManager
import com.unchunks.echomark.worker.ReembedAllWorker
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File

/** 埋め込みモデルの取り込み・同梱への復帰と、切り替え時の再埋め込みの登録。 */
@RunWith(AndroidJUnit4::class)
class EmbeddingModelManagerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var manager: EmbeddingModelManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = initTestWorkManager(context)
        Robolectric.setupContentProvider(ModelFileProvider::class.java, AUTHORITY)
        manager = newManager()
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
    }

    private fun newManager() = EmbeddingModelManager(
        context, workManager, TestDispatcherProvider(Dispatchers.Unconfined)
    )

    private fun uriOf(name: String) = Uri.parse("content://$AUTHORITY/$name")

    private fun reembedStates() = workManager.statesOf(ReembedAllWorker.WORK_NAME)

    @Test
    fun 何も取り込んでいなければ同梱のモデルを使う() {
        assertNull(manager.installedModel.value)
        assertNull(manager.modelFile())
        assertEquals(EmbeddingModelProfile.BUNDLED, manager.activeProfile())
        assertEquals("bundled", manager.sourceKey())
    }

    @Test
    fun 取り込むと選んだ種類のモデルになり_再埋め込みを積む() {
        manager.startImport(uriOf("v2.task"), EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL)

        val installed = manager.installedModel.value
        assertNotNull(installed)
        assertEquals(EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL, installed!!.profile)
        assertEquals("v2.task", installed.file.displayName)
        assertTrue(manager.modelFile()!!.isFile)
        assertEquals(EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL, manager.activeProfile())
        assertNotEquals("bundled", manager.sourceKey())
        assertTrue(manager.importState.value is ModelImportState.Succeeded)
        assertTrue(reembedStates().any { !it.isFinished })
    }

    @Test
    fun tfliteも取り込める() {
        manager.startImport(uriOf("model.tflite"), EmbeddingModelProfile.GEMMA_V1)

        assertEquals("local_embedding.tflite", manager.installedModel.value?.file?.fileName)
    }

    @Test
    fun 対応していない拡張子は取り込まない() {
        manager.startImport(uriOf("model.litertlm"), EmbeddingModelProfile.GEMMA_V1)

        val state = manager.importState.value
        assertTrue(state is ModelImportState.Failed)
        assertTrue((state as ModelImportState.Failed).error is ModelImportError.UnsupportedFormat)
        assertNull(manager.installedModel.value)
        assertTrue(reembedStates().isEmpty())
    }

    @Test
    fun 一覧に無い種類は取り込まない() {
        val unknown = EmbeddingModelProfile.GEMMA_V1.copy(id = "unknown", modelVersion = "unknown-v1")

        manager.startImport(uriOf("x.task"), unknown)

        assertEquals(ModelImportState.Failed(ModelImportError.UnknownModelProfile), manager.importState.value)
        assertNull(manager.installedModel.value)
    }

    @Test
    fun 同梱のモデルに戻すとファイルを消して再埋め込みを積む() {
        manager.startImport(uriOf("v2.task"), EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL)
        val file = manager.modelFile()!!
        workManager.cancelAllWork().result.get()

        manager.revertToBundled()

        assertNull(manager.installedModel.value)
        assertFalse(file.exists())
        assertEquals(EmbeddingModelProfile.BUNDLED, manager.activeProfile())
        assertEquals("bundled", manager.sourceKey())
        assertTrue(reembedStates().any { !it.isFinished })
    }

    @Test
    fun 取り込んだ内容は作り直したマネージャにも残る() {
        manager.startImport(uriOf("v2.task"), EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL)

        val reloaded = newManager()

        assertEquals(EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL, reloaded.activeProfile())
        assertEquals(manager.sourceKey(), reloaded.sourceKey())
    }

    @Test
    fun ファイルが欠けたら同梱のモデルとして扱う() {
        manager.startImport(uriOf("v2.task"), EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL)
        manager.modelFile()!!.delete()

        // 同梱のモデルで作ったベクトルに、取り込んだモデルの版を付けない
        assertEquals(EmbeddingModelProfile.BUNDLED, manager.activeProfile())
        assertEquals("bundled", manager.sourceKey())
    }

    @Test
    fun LLMの枠のファイルとは別に保存する() {
        manager.startImport(uriOf("v2.task"), EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL)

        val names = File(context.filesDir, "models").list().orEmpty().toSet()
        assertTrue(names.toString(), "local_embedding.task" in names)
        assertFalse(names.toString(), names.any { it.startsWith("local_llm") })
    }

    private companion object {
        const val AUTHORITY = "com.unchunks.echomark.test.modelfile"
    }
}

/** 名前に応じた小さなファイルを返すプロバイダ(SAF の取り込み元)。 */
class ModelFileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(uri.lastPathSegment.orEmpty(), CONTENT.size.toLong()))
        }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File.createTempFile("model", ".bin", context!!.cacheDir)
        file.writeBytes(CONTENT)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private companion object {
        val CONTENT = ByteArray(1024) { it.toByte() }
    }
}

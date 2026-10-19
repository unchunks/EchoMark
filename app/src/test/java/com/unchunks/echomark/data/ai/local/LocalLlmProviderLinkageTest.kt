package com.unchunks.echomark.data.ai.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * MediaPipe のクラスの初期化に失敗したときの端末内 LLM。
 * JVM には MediaPipe のネイティブライブラリが無く、LlmInference の静的初期化の System.loadLibrary が
 * UnsatisfiedLinkError になる(2回目以降は NoClassDefFoundError)。
 * Error のまま投げると、ワーカーは状態を「処理中」のまま残して失敗し、画面からの呼び出しではアプリごと落ちる。
 */
@RunWith(AndroidJUnit4::class)
class LocalLlmProviderLinkageTest {

    private lateinit var context: Context
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 取り込み済みのモデルがある状態にする(中身は読まれない。エンジンの作成の前にクラスの初期化で失敗する)
        modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        File(modelsDir, "model.task").writeBytes(byteArrayOf(1, 2, 3))
        // ModelManager の取り込み済みモデルの情報(ModelManager.METADATA_FILE_NAME)
        File(modelsDir, "local_llm.json").writeText(
            JSONObject()
                .put("fileName", "model.task")
                .put("displayName", "gemma-test.task")
                .put("sizeBytes", 3L)
                .put("importedAt", 1L)
                .toString()
        )
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
    }

    private fun provider(): LocalLlmProvider {
        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        return LocalLlmProvider(
            context,
            ModelManager(context, FakeBookmarkRepository(), dispatchers),
            FakeAppSettingsRepository(),
            dispatchers
        )
    }

    @Test
    fun ライブラリを初期化できないときは解析もチャットも端末内AIが使えない例外にする() = runBlocking {
        val provider = provider()
        val calls = listOf<suspend () -> Unit>(
            { provider.analyze(AnalysisInput(title = "タイトル", text = "本文", kind = ContentKind.MEMO), emptyList()) },
            { provider.chat("質問", emptyList(), emptyList()) }
        )
        // 1回目(UnsatisfiedLinkError)も2回目以降(NoClassDefFoundError)も、Error のまま投げない
        for (call in calls) {
            try {
                call()
                fail("端末内 AI の呼び出しが成功してしまった")
            } catch (e: LlmException.EngineUnavailable) {
                assertTrue("原因は ${e.cause}", e.cause is LinkageError)
            }
        }
    }
}

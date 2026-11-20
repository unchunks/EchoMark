package com.unchunks.echomark.data.ai.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.unchunks.components.llm.ondevice.EngineBackend
import com.unchunks.components.llm.ondevice.GenerationKind
import com.unchunks.components.llm.ondevice.LocalGeneration
import com.unchunks.components.llm.ondevice.LocalTextEngine
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CancellationException

/**
 * 用途ごとの端末内モデルでの、エンジンの読み込みと切り替え。同時に読み込むモデルは1つだけで、別のモデルを使う呼び出しが来たら
 * 読み込んでいるモデルを解放してから読み込む(モデルは数百MB〜数GBあり、2つ持つとメモリが足りなくなるため)。
 * LiteRT-LM は JVM で動かないため、エンジンは偽物に差し替える。
 */
@RunWith(AndroidJUnit4::class)
class LocalLlmProviderModelSwitchTest {

    private lateinit var context: Context
    private lateinit var modelsDir: File

    /** エンジンの読み込み・解放・生成を起きた順に記録する(`open:a`・`close:a`・`generate:a`) */
    private val events = Collections.synchronizedList(mutableListOf<String>())

    /** 読み込みに失敗させるモデルのファイル名 */
    private val brokenFiles = mutableSetOf<String>()

    /** 次の生成を、中断されるまで終わらせない */
    private var holdNextGeneration: CompletableDeferred<Unit>? = null

    private inner class RecordingEngine(val name: String) : LocalTextEngine {
        override fun start(
            prompt: String,
            kind: GenerationKind,
            onPartial: (String) -> Unit,
            onDone: (Throwable?) -> Unit
        ): LocalGeneration {
            events += "generate:$name"
            val held = holdNextGeneration
            holdNextGeneration = null
            if (held != null) {
                held.complete(Unit)
            } else {
                onPartial(if (kind == GenerationKind.CHAT) "回答" else ANALYSIS_JSON)
                onDone(null)
            }
            return object : LocalGeneration {
                override fun cancel() = onDone(CancellationException("中断"))
                override fun close() = Unit
            }
        }

        override fun close() {
            events += "close:$name"
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val models = JSONArray()
        listOf("a", "b").forEach { id ->
            File(modelsDir, "$id.litertlm").writeBytes(byteArrayOf(1, 2, 3))
            models.put(
                JSONObject()
                    .put("id", id)
                    .put("fileName", "$id.litertlm")
                    .put("displayName", "model-$id.litertlm")
                    .put("sizeBytes", 3L)
                    .put("importedAt", 1L)
            )
        }
        File(modelsDir, "local_llm_models.json").writeText(JSONObject().put("models", models).put("defaultId", "a").toString())
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
    }

    private val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
    private val modelManager by lazy { ModelManager(context, FakeBookmarkRepository(), dispatchers) }

    private val settings = FakeAppSettingsRepository()

    private val provider by lazy {
        LocalLlmProvider(context, modelManager, settings, dispatchers).apply {
            engineFactory = { file, _, _ ->
                check(file.name !in brokenFiles) { "読み込めないモデル" }
                val name = file.nameWithoutExtension
                events += "open:$name"
                RecordingEngine(name)
            }
        }
    }

    @Test
    fun 別のモデルを使う呼び出しが来たら解放してから読み込む() = runBlocking {
        provider.forModel("a").complete("1回目")
        provider.forModel("a").complete("2回目")
        provider.forModel("b").complete("3回目")

        assertEquals(listOf("open:a", "generate:a", "generate:a", "close:a", "open:b", "generate:b"), events)
    }

    @Test
    fun チャットも選んだモデルで答える() = runBlocking {
        val answer = provider.forModel("b").chat("質問", emptyList(), emptyList())

        assertEquals("回答", answer)
        assertEquals(listOf("open:b", "generate:b"), events)
    }

    @Test
    fun 選んでいない_一覧に無いモデルは既定のモデルで動かす() = runBlocking {
        provider.forModel(null).complete("1回目")
        provider.forModel("deleted").complete("2回目")
        provider.complete("3回目")

        assertEquals(listOf("open:a", "generate:a", "generate:a", "generate:a"), events)
    }

    @Test
    fun 既定のモデルを変えたら既定のモデルを使う呼び出しは新しい既定で動かす() = runBlocking {
        provider.forModel(null).complete("1回目")

        modelManager.setDefaultModel("b")
        provider.forModel(null).complete("2回目")

        assertEquals(listOf("open:a", "generate:a", "close:a", "open:b", "generate:b"), events)
    }

    @Test
    fun 先読みは選んだモデルを読み込み_読み込み済みなら読み直さない() = runBlocking {
        provider.forModel("b").prewarm()
        provider.forModel("b").prewarm()
        provider.forModel("b").complete("質問")

        assertEquals(listOf("open:b", "generate:b"), events)
    }

    @Test
    fun 選んだモデルのファイルが無ければ既定に逃げず_読み込んでいるエンジンも解放する() = runBlocking {
        provider.forModel("a").complete("質問")
        File(modelsDir, "b.litertlm").delete()

        try {
            provider.forModel("b").complete("質問")
            fail("モデルが無いのに成功してしまった")
        } catch (e: ModelNotAvailableException) {
            assertEquals(listOf("open:a", "generate:a", "close:a"), events)
        }
    }

    @Test
    fun 読み込めなかったことはそのモデルにだけ出し_ほかのモデルは使える() = runBlocking {
        brokenFiles += "b.litertlm"
        provider.forModel("a").complete("質問")

        try {
            provider.forModel("b").complete("質問")
            fail("読み込めないモデルで成功してしまった")
        } catch (e: IllegalStateException) {
            assertEquals("読み込めないモデル", e.message)
        }

        assertEquals(setOf("b"), modelManager.loadFailures.first().keys)
        provider.forModel("a").complete("質問")
        assertEquals(listOf("open:a", "generate:a", "close:a", "open:a", "generate:a"), events)
        assertEquals(setOf("b"), modelManager.loadFailures.first().keys)
    }

    @Test
    fun 別のモデルのチャットが割り込んだら解析は中断し_終わってから自分のモデルで読み込み直す() = runBlocking {
        val generating = CompletableDeferred<Unit>()
        holdNextGeneration = generating
        val analysis = async(Dispatchers.Default) {
            provider.forModel("a").analyze(
                AnalysisInput(title = "タイトル", text = "本文", kind = ContentKind.MEMO),
                emptyList()
            )
        }
        generating.await()

        provider.forModel("b").complete("画面で待っている生成")

        assertEquals("要約", analysis.await().summary)
        assertEquals(
            listOf("open:a", "generate:a", "close:a", "open:b", "generate:b", "close:b", "open:a", "generate:a"),
            events
        )
    }

    /** 測定の処理を、呼ばれた順に events へ記録する偽物に差し替える。 */
    private fun recordBenchmarks() {
        provider.benchmarkRunner = { file, backend ->
            events += "benchmark:${file.nameWithoutExtension}:$backend"
            BenchmarkInfo(1.5, 2.5, 256, 64, 80.0, 12.0)
        }
    }

    @Test
    fun 測るときは読み込んでいるモデルを解放してから測り_結果を返す() = runBlocking {
        recordBenchmarks()
        provider.forModel("a").complete("質問")

        val result = provider.benchmark("b")

        assertEquals(listOf("open:a", "generate:a", "close:a", "benchmark:b:CPU"), events)
        assertEquals("b", result.modelId)
        assertEquals(EngineBackend.CPU, result.backend)
        assertEquals(1.5, result.loadSeconds, 0.0)
        assertEquals(2.5, result.timeToFirstTokenSeconds, 0.0)
        assertEquals(80.0, result.prefillTokensPerSecond, 0.0)
        assertEquals(12.0, result.decodeTokensPerSecond, 0.0)
        assertEquals(256, result.prefillTokens)
        assertEquals(64, result.decodeTokens)
    }

    @Test
    fun 測った後の推論はモデルを読み込み直す() = runBlocking {
        recordBenchmarks()

        provider.benchmark("a")
        provider.forModel("a").complete("質問")

        assertEquals(listOf("benchmark:a:CPU", "open:a", "generate:a"), events)
    }

    @Test
    fun GPUを使う設定ならGPUで測り_測れなければCPUで測る() = runBlocking {
        val backends = mutableListOf<EngineBackend>()
        settings.setLocalLlmUseGpu(true)
        provider.benchmarkRunner = { _, backend ->
            backends += backend
            if (backend == EngineBackend.GPU) error("GPU では動かない")
            BenchmarkInfo(1.0, 1.0, 256, 64, 1.0, 1.0)
        }

        val result = provider.benchmark("a")

        assertEquals(listOf(EngineBackend.GPU, EngineBackend.CPU), backends)
        assertEquals(EngineBackend.CPU, result.backend)
    }

    @Test
    fun 一覧に無いモデルは既定に逃げずに測れないとする() = runBlocking {
        recordBenchmarks()

        try {
            provider.benchmark("deleted")
            fail("測れないはず")
        } catch (e: ModelNotAvailableException) {
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun 測れなくても読み込めない表示にはしない() = runBlocking {
        provider.benchmarkRunner = { _, _ -> error("書式が無い") }

        try {
            provider.benchmark("a")
            fail("測れないはず")
        } catch (e: IllegalStateException) {
            assertEquals(emptyMap<String, Any>(), modelManager.loadFailures.first())
        }
    }

    private companion object {
        const val ANALYSIS_JSON = """{"summary":"要約","tags":[],"category":"その他"}"""
    }
}

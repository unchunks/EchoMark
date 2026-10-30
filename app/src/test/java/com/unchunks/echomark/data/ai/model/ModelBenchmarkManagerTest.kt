package com.unchunks.echomark.data.ai.model

import com.unchunks.components.llm.ondevice.EngineBackend
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.ai.LocalModelBenchmarker
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelBenchmarkManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val file: File by lazy { File(temp.root, "models/benchmarks.json") }
    private val model = LocalModelInfo("a.litertlm", "a.litertlm", 1, 0)

    private fun result(
        modelId: String = model.id,
        backend: EngineBackend = EngineBackend.CPU,
        decode: Double = 12.5,
        memory: Long? = 1_000_000
    ) = ModelBenchmarkResult(
        modelId = modelId,
        backend = backend,
        measuredAt = 100,
        loadSeconds = 3.5,
        timeToFirstTokenSeconds = 4.25,
        prefillTokensPerSecond = 80.5,
        decodeTokensPerSecond = decode,
        prefillTokens = 256,
        decodeTokens = 64,
        peakMemoryBytes = memory
    )

    private fun manager(benchmarker: LocalModelBenchmarker) =
        ModelBenchmarkManager(benchmarker, ModelBenchmarkStore(file), TestDispatcherProvider(Dispatchers.Unconfined))

    @Test
    fun 測った結果を保存し_作り直しても読める() {
        val manager = manager { result() }

        manager.start(model)

        assertEquals(listOf(result()), manager.results.value)
        assertEquals(ModelBenchmarkState.Idle, manager.state.value)
        assertEquals(listOf(result()), manager { error("測らない") }.results.value)
    }

    @Test
    fun モデルと演算装置の組ごとに最後の結果を残す() {
        var next = result(decode = 10.0)
        val manager = manager { next }
        manager.start(model)

        next = result(backend = EngineBackend.GPU, decode = 40.0)
        manager.start(model)
        next = result(decode = 11.0)
        manager.start(model)

        assertEquals(
            setOf(result(backend = EngineBackend.GPU, decode = 40.0), result(decode = 11.0)),
            manager.results.value.toSet()
        )
    }

    @Test
    fun 測っている間はほかのモデルを測らない() {
        val gate = CompletableDeferred<Unit>()
        val measured = mutableListOf<String>()
        val manager = manager { id ->
            measured += id
            gate.await()
            result(modelId = id)
        }
        val other = LocalModelInfo("b.litertlm", "b.litertlm", 1, 0)

        manager.start(model)
        manager.start(other)

        assertEquals(ModelBenchmarkState.Running(model), manager.state.value)
        gate.complete(Unit)
        assertEquals(listOf(model.id), measured)
        assertEquals(ModelBenchmarkState.Idle, manager.state.value)
    }

    @Test
    fun 測れなければ失敗を知らせ_やり直せる() {
        var failure: Throwable? = ModelNotAvailableException()
        val manager = manager {
            failure?.let { throw it }
            result()
        }

        manager.start(model)
        assertEquals(ModelBenchmarkState.Failed(model, "モデルのファイルが見つかりません"), manager.state.value)
        assertTrue(manager.results.value.isEmpty())

        failure = null
        manager.start(model)
        assertEquals(ModelBenchmarkState.Idle, manager.state.value)
        assertEquals(1, manager.results.value.size)
    }

    @Test
    fun メモリ不足などのErrorでも測定中のまま残さない() {
        val manager = manager { throw OutOfMemoryError("native") }

        manager.start(model)

        assertTrue(manager.state.value is ModelBenchmarkState.Failed)
    }

    @Test
    fun モデルを削除したら結果も消す() {
        var next = result()
        val manager = manager { next }
        manager.start(model)
        next = result(modelId = "other")
        manager.start(model)

        manager.forget(model.id)

        assertEquals(listOf("other"), manager.results.value.map { it.modelId })
        assertEquals(listOf("other"), ModelBenchmarkStore(file).read().map { it.modelId })
    }

    @Test
    fun メモリを測れなかった結果も保存できる() {
        val store = ModelBenchmarkStore(file)

        store.write(listOf(result(memory = null)))

        assertNull(store.read().single().peakMemoryBytes)
    }

    @Test
    fun 壊れた記録は無いものとして扱い_読める行だけを残す() {
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        assertTrue(ModelBenchmarkStore(file).read().isEmpty())

        val store = ModelBenchmarkStore(file)
        store.write(listOf(result()))
        file.writeText(file.readText().replace("""{"results":[""", """{"results":[{"modelId":"x","backend":"TPU"},"""))
        assertEquals(listOf(result()), store.read())
    }
}

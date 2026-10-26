package com.unchunks.echomark.data.ai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 端末内モデルの GPU → CPU フォールバックの判断と、メモリの解放の判断(MediaPipe に依存しない部分)。 */
class LocalEnginePolicyTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** メモリ上の記録。書き込みの回数も数える。 */
    private class MemoryStore(
        var saved: Pair<String, EngineBackend>? = null,
        var trial: String? = null
    ) : BackendStore {
        var writes = 0
        override fun read(modelKey: String): EngineBackend? = saved?.takeIf { it.first == modelKey }?.second
        override fun write(modelKey: String, backend: EngineBackend) {
            writes++
            saved = modelKey to backend
        }
        override fun readGpuTrial(): String? = trial
        override fun writeGpuTrial(modelKey: String?) {
            trial = modelKey
        }
    }

    /** 指定した装置では作れない([failing])ファクトリ。試した装置の順を記録する。 */
    private class Factory(private val failing: Set<EngineBackend> = emptySet()) : (EngineBackend) -> String {
        val tried = mutableListOf<EngineBackend>()
        override fun invoke(backend: EngineBackend): String {
            tried += backend
            if (backend in failing) throw IllegalStateException("$backend は使えない")
            return "engine-$backend"
        }
    }

    @Test
    fun 初めてはGPUを先に試し_CPUを後に残す() {
        assertEquals(listOf(EngineBackend.GPU, EngineBackend.CPU), backendOrder(null))
        assertEquals(listOf(EngineBackend.GPU, EngineBackend.CPU), backendOrder(EngineBackend.GPU))
    }

    @Test
    fun CPUに落ちたと覚えていればGPUを試さない() {
        assertEquals(listOf(EngineBackend.CPU), backendOrder(EngineBackend.CPU))
    }

    @Test
    fun GPUで作れればそれを使い_覚える() {
        val store = MemoryStore()
        val factory = Factory()

        val created = BackendSelector(store).create("model-a", factory)

        assertEquals(EngineBackend.GPU, created.backend)
        assertEquals("engine-GPU", created.engine)
        assertEquals(listOf(EngineBackend.GPU), factory.tried)
        assertEquals("model-a" to EngineBackend.GPU, store.saved)
    }

    @Test
    fun GPUで作れなければCPUにして_CPUと覚える() {
        val store = MemoryStore()
        val factory = Factory(failing = setOf(EngineBackend.GPU))

        val created = BackendSelector(store).create("model-a", factory)

        assertEquals(EngineBackend.CPU, created.backend)
        assertEquals(listOf(EngineBackend.GPU, EngineBackend.CPU), factory.tried)
        assertEquals("model-a" to EngineBackend.CPU, store.saved)
    }

    @Test
    fun 次の読み込みでは失敗したGPUを試さない() {
        val store = MemoryStore()
        val selector = BackendSelector(store)
        selector.create("model-a", Factory(failing = setOf(EngineBackend.GPU)))

        val second = Factory()
        selector.create("model-a", second)

        assertEquals(listOf(EngineBackend.CPU), second.tried)
    }

    @Test
    fun アプリを再起動しても記録からCPUだけを試す() {
        val store = MemoryStore("model-a" to EngineBackend.CPU)
        val factory = Factory()

        BackendSelector(store).create("model-a", factory)

        assertEquals(listOf(EngineBackend.CPU), factory.tried)
        // 覚えている装置で作れただけなので、書き直さない
        assertEquals(0, store.writes)
    }

    @Test
    fun 取り込み直したモデルは前のモデルの記録を使わない() {
        val store = MemoryStore("model-a" to EngineBackend.CPU)
        val factory = Factory()

        BackendSelector(store).create("model-b", factory)

        assertEquals(listOf(EngineBackend.GPU), factory.tried)
        assertEquals("model-b" to EngineBackend.GPU, store.saved)
    }

    @Test
    fun GPUで覚えていても作れなくなればCPUに落とす() {
        val store = MemoryStore("model-a" to EngineBackend.GPU)
        val factory = Factory(failing = setOf(EngineBackend.GPU))

        val created = BackendSelector(store).create("model-a", factory)

        assertEquals(EngineBackend.CPU, created.backend)
        assertEquals("model-a" to EngineBackend.CPU, store.saved)
    }

    @Test
    fun どの装置でも作れなければ最後の失敗を投げる() {
        val factory = Factory(failing = setOf(EngineBackend.GPU, EngineBackend.CPU))

        try {
            BackendSelector(MemoryStore()).create("model-a", factory)
            fail("例外が投げられるはず")
        } catch (e: IllegalStateException) {
            assertEquals("CPU は使えない", e.message)
            // 先に失敗した GPU も辿れる
            assertEquals("GPU は使えない", e.suppressed.single().message)
        }
        assertEquals(listOf(EngineBackend.GPU, EngineBackend.CPU), factory.tried)
    }

    @Test
    fun GPUで推論に失敗したらCPUに切り替えて覚える() {
        val store = MemoryStore()
        val selector = BackendSelector(store)
        selector.create("model-a", Factory())

        selector.demoteToCpu("model-a")

        val next = Factory()
        selector.create("model-a", next)
        assertEquals(listOf(EngineBackend.CPU), next.tried)
        assertEquals("model-a" to EngineBackend.CPU, store.saved)
    }

    @Test
    fun ファイルに演算装置を保存して読み戻せる() {
        val file = File(temp.root, "models/local_llm_backend.json")
        val store = FileBackendStore(file)
        assertNull(store.read("model-a"))

        store.write("model-a", EngineBackend.CPU)

        assertEquals(EngineBackend.CPU, FileBackendStore(file).read("model-a"))
        // 別のモデルの記録としては読まない
        assertNull(FileBackendStore(file).read("model-b"))
        // 上書きできる
        store.write("model-b", EngineBackend.GPU)
        assertNull(FileBackendStore(file).read("model-a"))
        assertEquals(EngineBackend.GPU, FileBackendStore(file).read("model-b"))
    }

    @Test
    fun 壊れた記録は無いものとして扱う() {
        val file = temp.newFile("local_llm_backend.json").apply { writeText("{not json") }
        assertNull(FileBackendStore(file).read("model-a"))

        file.writeText("""{"key": "model-a", "backend": "TPU"}""")
        assertNull(FileBackendStore(file).read("model-a"))
    }

    @Test
    fun メモリが逼迫したか使われないプロセスのときだけ解放する() {
        // TRIM_MEMORY_RUNNING_LOW / RUNNING_CRITICAL
        assertTrue(shouldReleaseEngineOnTrim(10))
        assertTrue(shouldReleaseEngineOnTrim(15))
        // RUNNING_MODERATE(5)・UI_HIDDEN(20、画面が隠れただけ)は手放さない
        assertFalse(shouldReleaseEngineOnTrim(5))
        assertFalse(shouldReleaseEngineOnTrim(20))
        // BACKGROUND / MODERATE / COMPLETE
        assertTrue(shouldReleaseEngineOnTrim(40))
        assertTrue(shouldReleaseEngineOnTrim(60))
        assertTrue(shouldReleaseEngineOnTrim(80))
    }

    @Test
    fun 部分要約の時間の上限はGPUの方が短い() {
        assertTrue(partTimeoutMillis(EngineBackend.GPU) < partTimeoutMillis(EngineBackend.CPU))
        assertEquals(60_000L, partTimeoutMillis(EngineBackend.GPU))
        assertEquals(90_000L, partTimeoutMillis(EngineBackend.CPU))
    }

    @Test
    fun 作ったエンジンをそのまま返す() {
        val engine = Any()
        val created = BackendSelector(MemoryStore()).create("model-a") { engine }

        assertSame(engine, created.engine)
    }

    @Test
    fun GPUを使う設定でなければCPUだけを試す() {
        assertEquals(listOf(EngineBackend.CPU), backendOrder(null, allowGpu = false))
        assertEquals(listOf(EngineBackend.CPU), backendOrder(EngineBackend.GPU, allowGpu = false))

        val store = MemoryStore("model-a" to EngineBackend.GPU)
        val factory = Factory()
        val created = BackendSelector(store).create("model-a", allowGpu = false, factory = factory)

        assertEquals(EngineBackend.CPU, created.backend)
        assertEquals(listOf(EngineBackend.CPU), factory.tried)
        assertNull(store.trial)
    }

    @Test
    fun GPUのライブラリが読めないErrorでもCPUに落とす() {
        val store = MemoryStore()
        val created = BackendSelector(store).create("model-a") { backend ->
            if (backend == EngineBackend.GPU) throw UnsatisfiedLinkError("libOpenCL.so が無い")
            "engine-$backend"
        }

        assertEquals(EngineBackend.CPU, created.backend)
        assertEquals("model-a" to EngineBackend.CPU, store.saved)
        // 失敗した GPU の試行の印は残さない
        assertNull(store.trial)
    }

    @Test
    fun GPUを試す間は印を残し_最初の推論に成功したら消す() {
        val store = MemoryStore()
        val selector = BackendSelector(store)

        selector.create("model-a", Factory())
        assertEquals("model-a", store.trial)

        selector.confirmGpu()
        assertNull(store.trial)
    }

    @Test
    fun 前回GPUを試している最中に落ちていれば以後CPUだけを使う() {
        // 前のプロセスが GPU で作った直後(推論の成功前)にネイティブのクラッシュで終了した跡
        val store = MemoryStore("model-a" to EngineBackend.GPU, trial = "model-a")
        val factory = Factory()

        val created = BackendSelector(store).create("model-a", factory)

        assertEquals(EngineBackend.CPU, created.backend)
        assertEquals(listOf(EngineBackend.CPU), factory.tried)
        assertEquals("model-a" to EngineBackend.CPU, store.saved)
        assertNull(store.trial)
    }

    @Test
    fun 同じプロセスで作り直すときは自分の印を落ちた跡とみなさない() {
        val store = MemoryStore()
        val selector = BackendSelector(store)
        selector.create("model-a", Factory())

        // 推論の前にアイドルで解放され、作り直す
        val second = Factory()
        val created = selector.create("model-a", second)

        assertEquals(EngineBackend.GPU, created.backend)
        assertEquals(listOf(EngineBackend.GPU), second.tried)
    }

    @Test
    fun 別のモデルの試行の印は消すだけでCPUにしない() {
        val store = MemoryStore(trial = "old-model")
        val factory = Factory()

        val created = BackendSelector(store).create("model-a", factory)

        assertEquals(EngineBackend.GPU, created.backend)
        assertEquals("model-a" to EngineBackend.GPU, store.saved)
    }

    @Test
    fun GPUで推論に失敗したら試行の印も消す() {
        val store = MemoryStore()
        val selector = BackendSelector(store)
        selector.create("model-a", Factory())

        selector.demoteToCpu("model-a")

        assertNull(store.trial)
    }

    @Test
    fun GPUの試行の印をファイルに残して読み戻せる() {
        val file = File(temp.root, "models/local_llm_backend.json")
        val store = FileBackendStore(file)
        assertNull(store.readGpuTrial())

        store.writeGpuTrial("model-a")
        assertEquals("model-a", FileBackendStore(file).readGpuTrial())

        store.writeGpuTrial(null)
        assertNull(FileBackendStore(file).readGpuTrial())
    }

    @Test
    fun 入力が文脈長を超えたMediaPipeの失敗を見分ける() {
        val tooLong = RuntimeException(
            "INVALID_ARGUMENT: Input is too long for the model to process: current_step(0) + input_size(4210) was not less than maxTokens(4096)."
        )
        assertTrue(isInputTooLong(tooLong))
        // 包まれていても見分ける
        assertTrue(isInputTooLong(IllegalStateException("生成に失敗", tooLong)))
        assertFalse(isInputTooLong(RuntimeException("Failed to initialize session")))
    }
}

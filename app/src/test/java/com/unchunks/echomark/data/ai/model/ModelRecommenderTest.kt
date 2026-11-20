package com.unchunks.echomark.data.ai.model

import com.unchunks.components.llm.ondevice.EngineBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommenderTest {

    private val gb = 1_000_000_000L
    private val plenty = 100 * gb

    private fun entry(
        id: String,
        sizeBytes: Long = gb,
        memoryMb: Int? = null,
        requiresAgreement: Boolean = false
    ) = ModelCatalogEntry(
        id = id,
        name = id,
        repo = "litert-community/$id",
        revision = "0".repeat(40),
        fileName = "$id.litertlm",
        sizeBytes = sizeBytes,
        sha256 = "0".repeat(64),
        license = "Apache-2.0",
        requiresAgreement = requiresAgreement,
        suitedFor = "テスト",
        published = memoryMb?.let { PublishedModelPerformance("Pixel", EngineBackend.CPU, 10.0, it) }
    )

    private fun device(ramGb: Long, storage: Long = plenty) = DeviceSpecs(ramGb * gb, storage)

    private fun recommend(
        device: DeviceSpecs,
        catalog: List<ModelCatalogEntry>,
        installed: InstalledModels = InstalledModels(),
        benchmarks: List<ModelBenchmarkResult> = emptyList()
    ) = ModelRecommender.recommend(device, catalog, installed, benchmarks)

    private fun installedOf(entry: ModelCatalogEntry) = LocalModelInfo("${entry.id}.litertlm", entry.fileName, entry.sizeBytes, 0, id = entry.id)

    private fun result(modelId: String, decode: Double, backend: EngineBackend = EngineBackend.CPU) =
        ModelBenchmarkResult(modelId, backend, 0, 1.0, 1.0, 50.0, decode, 256, 64, null)

    @Test
    fun メモリの半分までは合い_7割までは動くかもしれず_それを超えると重い() {
        val judged = recommend(
            device(8),
            listOf(entry("half", memoryMb = 4000), entry("seventy", memoryMb = 5600), entry("over", memoryMb = 5601))
        ).associate { it.entry.id to it.fit }

        assertEquals(ModelFit.RECOMMENDED, judged["half"])
        assertEquals(ModelFit.POSSIBLE, judged["seventy"])
        assertEquals(ModelFit.TOO_HEAVY, judged["over"])
    }

    @Test
    fun 公開の測定例が無ければファイルの大きさの2倍を目安にする() {
        val judged = recommend(device(4), listOf(entry("small", sizeBytes = gb), entry("big", sizeBytes = 2 * gb))).associateBy { it.entry.id }

        assertEquals(ModelFit.RECOMMENDED, judged.getValue("small").fit)
        assertTrue(judged.getValue("small").reasons.single().contains("ファイルの大きさからの目安"))
        assertEquals(ModelFit.TOO_HEAVY, judged.getValue("big").fit)
    }

    @Test
    fun 公開の測定例があれば理由にそう書く() {
        val judged = recommend(device(8), listOf(entry("model", memoryMb = 1000))).single()

        assertTrue(judged.reasons.single().contains("公開の測定例"))
    }

    @Test
    fun いちばんのおすすめはメモリの3分の1に収まるいちばん大きいモデルで_先頭に並べる() {
        val judged = recommend(
            device(9),
            listOf(entry("small", memoryMb = 1000), entry("middle", memoryMb = 2900), entry("large", memoryMb = 4400))
        )

        assertEquals(listOf("middle"), judged.filter { it.isTopPick }.map { it.entry.id })
        assertEquals("middle", judged.first().entry.id)
        assertEquals(ModelFit.RECOMMENDED, judged.first { it.entry.id == "large" }.fit)
    }

    @Test
    fun 収まるものが無ければ合うものの中でいちばん小さいモデルを勧める() {
        val judged = recommend(device(4), listOf(entry("a", memoryMb = 1500), entry("b", memoryMb = 1900)))

        assertEquals("a", judged.single { it.isTopPick }.entry.id)
    }

    @Test
    fun 同意の要らないモデルを先に勧める() {
        val judged = recommend(
            device(8),
            listOf(entry("gated", memoryMb = 2500, requiresAgreement = true), entry("open", memoryMb = 1000))
        )

        assertEquals("open", judged.single { it.isTopPick }.entry.id)
    }

    @Test
    fun 合うモデルが無ければいちばんのおすすめは無い() {
        val judged = recommend(device(2), listOf(entry("heavy", memoryMb = 3000)))

        assertFalse(judged.single().isTopPick)
        assertEquals(ModelFit.TOO_HEAVY, judged.single().fit)
    }

    @Test
    fun 測った結果は目安より優先する() {
        val heavy = entry("heavy", memoryMb = 7000)

        val judged = recommend(
            device(8),
            listOf(heavy),
            InstalledModels(listOf(installedOf(heavy))),
            listOf(result(heavy.id, decode = 12.0))
        ).single()

        assertEquals(ModelFit.RECOMMENDED, judged.fit)
        assertTrue(judged.reasons.last(), judged.reasons.last().contains("12.0 トークン/秒(CPU)"))
    }

    @Test
    fun 演算装置が複数あれば速い方の結果で見る() {
        val model = entry("model", memoryMb = 1000)

        val judged = recommend(
            device(8),
            listOf(model),
            InstalledModels(listOf(installedOf(model))),
            listOf(result(model.id, decode = 2.0), result(model.id, decode = 30.0, backend = EngineBackend.GPU))
        ).single()

        assertEquals(ModelFit.RECOMMENDED, judged.fit)
        assertTrue(judged.reasons.last().contains("30.0 トークン/秒(GPU)"))
    }

    @Test
    fun 測って遅いモデルは勧めず_それより大きいモデルも勧めない() {
        val small = entry("small", sizeBytes = gb, memoryMb = 1000)
        val bigger = entry("bigger", sizeBytes = 2 * gb, memoryMb = 1500)
        val smaller = entry("smaller", sizeBytes = gb / 2, memoryMb = 800)

        val judged = recommend(
            device(16),
            listOf(small, bigger, smaller),
            InstalledModels(listOf(installedOf(small))),
            listOf(result(small.id, decode = 3.0))
        ).associateBy { it.entry.id }

        assertEquals(ModelFit.POSSIBLE, judged.getValue("small").fit)
        assertEquals(ModelFit.POSSIBLE, judged.getValue("bigger").fit)
        assertTrue(judged.getValue("bigger").reasons.last().contains("さらに遅く"))
        assertEquals(ModelFit.RECOMMENDED, judged.getValue("smaller").fit)
        assertTrue(judged.getValue("smaller").isTopPick)
    }

    @Test
    fun 取り込む空きが無いモデルは勧めないが_取り込み済みなら関係ない() {
        val model = entry("model", sizeBytes = gb, memoryMb = 1000)

        val missing = recommend(device(8, storage = gb), listOf(model)).single()
        val installed = recommend(device(8, storage = gb), listOf(model), InstalledModels(listOf(installedOf(model)))).single()

        assertEquals(ModelFit.POSSIBLE, missing.fit)
        assertTrue(missing.reasons.last().contains("空きが必要"))
        assertEquals(ModelFit.RECOMMENDED, installed.fit)
    }

    @Test
    fun 空きが無くても重いモデルの判断は軽くしない() {
        val heavy = entry("heavy", sizeBytes = gb, memoryMb = 3000)

        assertEquals(ModelFit.TOO_HEAVY, recommend(device(2, storage = gb), listOf(heavy)).single().fit)
    }

    @Test
    fun GPUがはっきり速いときだけ伝える() {
        assertTrue(ModelRecommender.isGpuFaster(listOf(result("a", 10.0), result("a", 12.0, EngineBackend.GPU))))
        assertFalse(ModelRecommender.isGpuFaster(listOf(result("a", 10.0), result("a", 11.9, EngineBackend.GPU))))
        assertFalse("片方だけでは比べない", ModelRecommender.isGpuFaster(listOf(result("a", 10.0))))
        assertFalse("別のモデルとは比べない", ModelRecommender.isGpuFaster(listOf(result("a", 1.0), result("b", 50.0, EngineBackend.GPU))))
    }

    @Test
    fun 実際のカタログで端末の大きさごとに勧めるモデルが変わる() {
        fun topPick(ramGb: Long) = recommend(device(ramGb), ModelCatalog.entries).firstOrNull { it.isTopPick }?.entry?.id

        assertEquals("qwen3-0.6b-int4", topPick(3))
        assertEquals("gemma-4-e2b", topPick(6))
        assertEquals("gemma-4-e4b", topPick(12))
    }
}

package com.unchunks.echomark.data.ai.model

import com.unchunks.components.filestorage.formatBytes
import com.unchunks.components.llm.ondevice.EngineBackend
import java.util.Locale

/** この端末にモデルが合うかの判断。良い順。 */
enum class ModelFit {
    /** 余裕を持って動くと見込める */
    RECOMMENDED,

    /** 動くかもしれないが、余裕が無い・遅い */
    POSSIBLE,

    /** メモリが足りないおそれがある */
    TOO_HEAVY
}

/**
 * [entry] がこの端末に合うかの判断。
 * @property isTopPick この端末にいちばん勧めるモデルか(一覧で1つだけ)
 * @property reasons 判断の理由(画面に出す)
 */
data class ModelRecommendation(
    val entry: ModelCatalogEntry,
    val fit: ModelFit,
    val isTopPick: Boolean,
    val reasons: List<String>
)

/**
 * おすすめのモデルのうち、どれがこの端末に合うかを決める。Android に依存しない純粋な処理で、単体テストできる。
 *
 * - メモリ: モデルが使うメモリ(公開の測定例。無ければファイルの大きさからの目安)を、端末のメモリと比べる。アプリが使える
 *   メモリは OS やほかのアプリの分だけ少ないため、端末のメモリの半分までを余裕がある、7 割までを動くかもしれない、とする
 * - 測った結果: この端末で測ったモデル([ModelBenchmarkResult])は、目安より測った結果を優先する。
 *   遅いモデルがあれば、それより大きいモデルも勧めない
 * - SoC は画面に出すだけで判断には使わない。SoC ごとの速さの確かな表は無く、速さは測った結果で見るため
 */
object ModelRecommender {
    /** 端末のメモリのこの割合までなら、余裕を持って動くとみなす */
    private const val COMFORTABLE_RAM_PERCENT = 50

    /** 端末のメモリのこの割合までなら、動くかもしれないとみなす */
    private const val POSSIBLE_RAM_PERCENT = 70

    /** いちばんのおすすめは、端末のメモリのこの割合までのモデルから選ぶ(大きいモデルは読み込みも回答も遅いため) */
    private const val TOP_PICK_RAM_PERCENT = 33

    /** 公開の測定例が無いモデルの、ファイルの大きさに対するメモリの目安(モデルのほかに文脈などの分を使う) */
    private const val MEMORY_PER_FILE_SIZE = 2

    /** 出力の速さがこれ未満なら遅いとみなす(チャットの 200 トークンの回答に 40 秒以上かかる) */
    private const val SLOW_DECODE_TOKENS_PER_SECOND = 5.0

    /** GPU の方が速いと伝える、CPU に対する出力の速さの比 */
    private const val GPU_FASTER_RATIO = 1.2

    private const val BYTES_PER_MEGABYTE = 1_000_000L

    /** [catalog] のそれぞれの判断を、合う順(いちばんのおすすめが先頭)に返す。 */
    fun recommend(
        device: DeviceSpecs,
        catalog: List<ModelCatalogEntry>,
        installed: InstalledModels,
        benchmarks: List<ModelBenchmarkResult>
    ): List<ModelRecommendation> {
        val fastestByModel = benchmarks.groupBy { it.modelId }.mapValues { (_, results) -> results.maxBy { it.decodeTokensPerSecond } }
        val slowest = installed.models
            .mapNotNull { model -> fastestByModel[model.id]?.let { model to it } }
            .filter { (_, result) -> result.decodeTokensPerSecond < SLOW_DECODE_TOKENS_PER_SECOND }
        val judged = catalog.map { entry ->
            val model = installed.modelFor(entry)
            val measured = model?.let { fastestByModel[it.id] }
            // 測った結果が遅いモデルより大きいモデルは、もっと遅いと見込む
            val slowerThan = slowest.filter { (slow, _) -> entry.sizeBytes > slow.sizeBytes }.maxByOrNull { (slow, _) -> slow.sizeBytes }
            judge(entry, device, isInstalled = model != null, measured, slowerThan?.second)
        }
        val top = topPick(judged, device)
        return judged
            .map { it.copy(isTopPick = it.entry == top) }
            .sortedWith(compareBy({ !it.isTopPick }, { it.fit }))
    }

    /** [benchmarks] に CPU と GPU の両方があるモデルで、GPU の方がはっきり速いか。 */
    fun isGpuFaster(benchmarks: List<ModelBenchmarkResult>): Boolean =
        benchmarks.groupBy { it.modelId }.values.any { results ->
            val cpu = results.firstOrNull { it.backend == EngineBackend.CPU } ?: return@any false
            val gpu = results.firstOrNull { it.backend == EngineBackend.GPU } ?: return@any false
            gpu.decodeTokensPerSecond >= cpu.decodeTokensPerSecond * GPU_FASTER_RATIO
        }

    private fun judge(
        entry: ModelCatalogEntry,
        device: DeviceSpecs,
        isInstalled: Boolean,
        measured: ModelBenchmarkResult?,
        slowerThan: ModelBenchmarkResult?
    ): ModelRecommendation {
        val reasons = mutableListOf<String>()
        var fit = memoryFit(entry, device, reasons)
        if (measured != null) {
            // 実際に動いた結果は、メモリの目安より確か
            val slow = measured.decodeTokensPerSecond < SLOW_DECODE_TOKENS_PER_SECOND
            fit = if (slow) ModelFit.POSSIBLE else ModelFit.RECOMMENDED
            reasons += "この端末で測った出力は ${speed(measured)}です" + if (slow) "。回答に時間がかかります" else ""
        } else if (slowerThan != null && fit == ModelFit.RECOMMENDED) {
            fit = ModelFit.POSSIBLE
            reasons += "より小さいモデルの出力が ${speed(slowerThan)}のため、さらに遅くなりそうです"
        }
        val requiredStorage = entry.sizeBytes * 2 + ModelImportValidator.STORAGE_MARGIN_BYTES
        if (!isInstalled && device.availableStorageBytes < requiredStorage) {
            fit = maxOf(fit, ModelFit.POSSIBLE)
            reasons += "取り込むには ${formatBytes(requiredStorage)}の空きが必要です(いまの空き: ${formatBytes(device.availableStorageBytes)})"
        }
        return ModelRecommendation(entry, fit, isTopPick = false, reasons)
    }

    private fun memoryFit(entry: ModelCatalogEntry, device: DeviceSpecs, reasons: MutableList<String>): ModelFit {
        val memory = memoryBytes(entry)
        val basis = if (entry.published?.memoryMegabytes != null) "公開の測定例" else "ファイルの大きさからの目安"
        val fit = when {
            memory * 100 <= device.totalRamBytes * COMFORTABLE_RAM_PERCENT -> ModelFit.RECOMMENDED
            memory * 100 <= device.totalRamBytes * POSSIBLE_RAM_PERCENT -> ModelFit.POSSIBLE
            else -> ModelFit.TOO_HEAVY
        }
        val verdict = when (fit) {
            ModelFit.RECOMMENDED -> "余裕があります"
            ModelFit.POSSIBLE -> "余裕がありません"
            ModelFit.TOO_HEAVY -> "足りないおそれがあります"
        }
        reasons += "使うメモリは約 ${formatBytes(memory)}($basis)で、この端末のメモリ ${formatBytes(device.totalRamBytes)} に対して$verdict"
        return fit
    }

    /**
     * いちばんのおすすめ。余裕を持って動くモデルのうち、取得の手間が少なく(利用規約への同意が要らない)、
     * 端末のメモリの [TOP_PICK_RAM_PERCENT] % に収まるいちばん大きいモデル。収まるものが無ければいちばん小さいモデル。
     * 大きいほど回答の品質が良いと見込むが、読み込みと回答が遅くなるため、上限を設ける。
     */
    private fun topPick(judged: List<ModelRecommendation>, device: DeviceSpecs): ModelCatalogEntry? {
        val candidates = judged.filter { it.fit == ModelFit.RECOMMENDED }.map { it.entry }
            .let { list -> list.filterNot { it.requiresAgreement }.ifEmpty { list } }
        val light = candidates.filter { memoryBytes(it) * 100 <= device.totalRamBytes * TOP_PICK_RAM_PERCENT }
        return light.maxByOrNull { memoryBytes(it) } ?: candidates.minByOrNull { memoryBytes(it) }
    }

    private fun memoryBytes(entry: ModelCatalogEntry): Long =
        entry.published?.memoryMegabytes?.let { it * BYTES_PER_MEGABYTE } ?: (entry.sizeBytes * MEMORY_PER_FILE_SIZE)

    private fun speed(result: ModelBenchmarkResult): String =
        String.format(Locale.ROOT, "%.1f", result.decodeTokensPerSecond) + " トークン/秒(${result.backend.name})"
}

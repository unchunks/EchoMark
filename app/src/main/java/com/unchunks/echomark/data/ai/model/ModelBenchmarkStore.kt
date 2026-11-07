package com.unchunks.echomark.data.ai.model

import com.unchunks.components.llm.ondevice.EngineBackend
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * 端末内モデルの測定の結果を [file] に JSON で保存する。モデルと演算装置の組ごとに、最後の結果を1件ずつ持つ。
 * 結果はこの端末でだけ意味があるため、バックアップの対象外の `filesDir/models/` に置く(DB にも入れない)。
 * 読み書きの失敗は無視する(結果が無いだけで、測り直せる)。
 */
class ModelBenchmarkStore(private val file: File) {

    /** 保存している結果。モデルの id ごと。壊れた行は読み飛ばす */
    fun read(): List<ModelBenchmarkResult> = try {
        if (!file.isFile) {
            emptyList()
        } else {
            val array = JSONObject(file.readText()).getJSONArray(RESULTS)
            (0 until array.length()).mapNotNull { resultOf(array.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        Timber.w(e, "端末内モデルの測定の結果を読めない")
        emptyList()
    }

    fun write(results: List<ModelBenchmarkResult>) {
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject().put(RESULTS, JSONArray(results.map(::jsonOf))).toString()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        } catch (e: Exception) {
            Timber.w(e, "端末内モデルの測定の結果を保存できない")
        }
    }

    private fun jsonOf(result: ModelBenchmarkResult) = JSONObject()
        .put("modelId", result.modelId)
        .put("backend", result.backend.name)
        .put("measuredAt", result.measuredAt)
        .put("loadSeconds", result.loadSeconds)
        .put("timeToFirstTokenSeconds", result.timeToFirstTokenSeconds)
        .put("prefillTokensPerSecond", result.prefillTokensPerSecond)
        .put("decodeTokensPerSecond", result.decodeTokensPerSecond)
        .put("prefillTokens", result.prefillTokens)
        .put("decodeTokens", result.decodeTokens)
        .apply { result.peakMemoryBytes?.let { put("peakMemoryBytes", it) } }

    private fun resultOf(json: JSONObject): ModelBenchmarkResult? = try {
        ModelBenchmarkResult(
            modelId = json.getString("modelId"),
            backend = EngineBackend.valueOf(json.getString("backend")),
            measuredAt = json.getLong("measuredAt"),
            loadSeconds = json.getDouble("loadSeconds"),
            timeToFirstTokenSeconds = json.getDouble("timeToFirstTokenSeconds"),
            prefillTokensPerSecond = json.getDouble("prefillTokensPerSecond"),
            decodeTokensPerSecond = json.getDouble("decodeTokensPerSecond"),
            prefillTokens = json.getInt("prefillTokens"),
            decodeTokens = json.getInt("decodeTokens"),
            peakMemoryBytes = if (json.has("peakMemoryBytes")) json.getLong("peakMemoryBytes") else null
        )
    } catch (e: Exception) {
        Timber.w(e, "端末内モデルの測定の結果の1件を読めない")
        null
    }

    private companion object {
        const val RESULTS = "results"
    }
}

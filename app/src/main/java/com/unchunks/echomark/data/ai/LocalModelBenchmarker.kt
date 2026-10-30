package com.unchunks.echomark.data.ai

import com.unchunks.echomark.data.ai.model.ModelBenchmarkResult

/**
 * 取り込んだ端末内モデルをこの端末で測る。
 * 実装(LocalLlmProvider)は、読み込んでいるモデルを解放してから測る。測っている間、端末内 AI の推論は待たされる。
 */
fun interface LocalModelBenchmarker {
    /**
     * @throws com.unchunks.echomark.domain.provider.ModelNotAvailableException モデルが一覧に無い・ファイルが無いとき
     */
    suspend fun benchmark(modelId: String): ModelBenchmarkResult
}

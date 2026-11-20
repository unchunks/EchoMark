package com.unchunks.echomark.data.ai.model

import com.unchunks.components.llm.ondevice.EngineBackend

/**
 * 取り込んだ端末内モデルを、この端末で測った結果。
 * @property modelId 測ったモデルの id
 * @property backend 測った演算装置(GPU で作れなければ CPU で測る)
 * @property measuredAt 測った日時(epoch millis)
 * @property loadSeconds モデルの読み込み(エンジンの作成)にかかった時間
 * @property timeToFirstTokenSeconds 生成を始めてから最初のトークンが出るまでの時間
 * @property prefillTokensPerSecond 入力を読み取る速さ
 * @property decodeTokensPerSecond 回答を出力する速さ
 * @property prefillTokens 測ったときの入力のトークン数
 * @property decodeTokens 測ったときの出力のトークン数
 * @property peakMemoryBytes 測っている間に増えたメモリの最大。測れなければ null
 */
data class ModelBenchmarkResult(
    val modelId: String,
    val backend: EngineBackend,
    val measuredAt: Long,
    val loadSeconds: Double,
    val timeToFirstTokenSeconds: Double,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val peakMemoryBytes: Long?
)

/** 端末内モデルの測定の状態。 */
sealed interface ModelBenchmarkState {
    data object Idle : ModelBenchmarkState

    /** [model] を測っている。読み込みと生成で 1 分前後かかる(遅い端末ではそれ以上) */
    data class Running(val model: LocalModelInfo) : ModelBenchmarkState

    data class Failed(val model: LocalModelInfo, val message: String) : ModelBenchmarkState
}

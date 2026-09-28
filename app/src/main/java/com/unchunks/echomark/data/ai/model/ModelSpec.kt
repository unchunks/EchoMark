package com.unchunks.echomark.data.ai.model

/**
 * オンデバイスで利用する AI モデルの仕様。
 *
 * @property sizeBytes 期待するファイルサイズ。0 の場合は不明として、サイズ検証をスキップする
 * @property version モデルのバージョン。更新時に上げると再ダウンロードの目安になる
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val version: String
)

object ModelSpecs {

    // TODO: 実際の配布 URL に差し替える。
    //  Hugging Face の litert-community/Gemma3-1B-IT は利用規約への同意(認証)が必要なため、
    //  自前ホスティング(GCS/R2 等)へ置くか、認証ヘッダ付与の仕組みを追加すること。
    const val GEMMA_LLM_URL = "https://example.invalid/models/gemma3-1b-it-int4.task"

    // TODO: 実ファイルのサイズ(概算 500MB 前後)を確定したら設定する
    private const val GEMMA_LLM_SIZE_BYTES = 0L

    val GEMMA_LLM = ModelSpec(
        id = "gemma3-1b-it-int4",
        displayName = "Gemma 3 1B (要約・チャット用 LLM)",
        fileName = "gemma3-1b-it-int4.task",
        downloadUrl = GEMMA_LLM_URL,
        sizeBytes = GEMMA_LLM_SIZE_BYTES,
        version = "1"
    )

    // TODO: EmbeddingGemma(現在は assets 同梱)も ModelSpec 化して ModelManager 管理に移行する。
    //  OnDeviceEmbeddingProvider を ModelManager.file(spec) 参照に切り替える。

    /** ModelManager が管理するモデル一覧。 */
    val all: List<ModelSpec> = listOf(GEMMA_LLM)

    fun findById(id: String?): ModelSpec? = all.firstOrNull { it.id == id }
}

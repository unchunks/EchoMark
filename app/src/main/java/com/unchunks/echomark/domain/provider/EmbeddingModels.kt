package com.unchunks.echomark.domain.provider

import com.unchunks.components.embedding.EmbeddingModelProfile

/** このアプリで使う埋め込みモデル(同梱するものと、取り込めるもの)。 */
object EmbeddingModels {
    /** アセット同梱のモデル(取り込み済みのモデルが無いときに使う)。 */
    val BUNDLED = EmbeddingModelProfile.GEMMA_V1

    /** 同梱のモデルの assets 内の場所 */
    const val BUNDLED_ASSET_PATH = "embeddinggemma-300m/embedding_gemma.task"

    /** 取り込み時に選べるプロファイル。ここに無いモデルは取り込めない。 */
    val IMPORTABLE: List<EmbeddingModelProfile> =
        listOf(EmbeddingModelProfile.GEMMA_V1, EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL)

    fun findById(id: String): EmbeddingModelProfile? = IMPORTABLE.firstOrNull { it.id == id }
}

package com.unchunks.echomark.domain.provider

import com.unchunks.components.embedding.EmbeddingModelProfile
import com.unchunks.components.llm.ApiProvider

/** このアプリで使う埋め込みモデル(同梱するもの・取り込めるもの・クラウド API のもの)。 */
object EmbeddingModels {
    /** アセット同梱のモデル(取り込み済みのモデルが無いときに使う)。 */
    val BUNDLED = EmbeddingModelProfile.GEMMA_V1

    /** 同梱のモデルの assets 内の場所 */
    const val BUNDLED_ASSET_PATH = "embeddinggemma-300m/embedding_gemma.task"

    /** 取り込み時に選べるプロファイル。ここに無いモデルは取り込めない。 */
    val IMPORTABLE: List<EmbeddingModelProfile> =
        listOf(EmbeddingModelProfile.GEMMA_V1, EmbeddingModelProfile.GEMMA_V2_EXPERIMENTAL)

    fun findById(id: String): EmbeddingModelProfile? = IMPORTABLE.firstOrNull { it.id == id }

    /**
     * クラウド API に求めるベクトルの次元。ベクトルの索引(ObjectBox の HNSW。`EmbeddingEntity`)が 768 次元で固定のため、
     * 端末内のモデルと同じ 768 にする(どちらの提供元のモデルも Matryoshka 表現で、次元を減らしても使える)。
     */
    const val CLOUD_DIMENSIONS = 768

    /**
     * Gemini の埋め込み API(gemini-embedding-2 を 768 次元で使う)。8,192 トークンまで読める。
     * TODO: しきい値は実機で評価していない(`docs/embedding-evaluation.md` の評価データで測って決める)。
     *   Gemini の埋め込みは関係の無い文どうしでも類似度が高めに出るため、端末内のモデルより厳しめにしている。
     */
    val CLOUD_GEMINI = EmbeddingModelProfile(
        id = "cloud-gemini-embedding-2",
        displayName = "Gemini(gemini-embedding-2)",
        modelVersion = "cloud-gemini-embedding-2-d768-v1",
        dimensions = CLOUD_DIMENSIONS,
        maxSearchDistance = 0.35,
        minRagSimilarity = 0.6,
        maxInputTokens = 8_192,
        maxContentChars = 3_000
    )

    /**
     * OpenAI の埋め込み API(text-embedding-3-small を 768 次元で使う)。8,192 トークンまで読める。
     * TODO: しきい値は実機で評価していない(`docs/embedding-evaluation.md` の評価データで測って決める)。
     *   text-embedding-3 は関係のある文どうしでも類似度が 0.3〜0.6 ほどに収まるため、端末内のモデルより緩めにしている。
     */
    val CLOUD_OPENAI = EmbeddingModelProfile(
        id = "cloud-openai-text-embedding-3-small",
        displayName = "OpenAI(text-embedding-3-small)",
        modelVersion = "cloud-openai-text-embedding-3-small-d768-v1",
        dimensions = CLOUD_DIMENSIONS,
        maxSearchDistance = 0.6,
        minRagSimilarity = 0.35,
        maxInputTokens = 8_192,
        maxContentChars = 3_000
    )

    /** [provider] の埋め込み API で作るときのプロファイル。埋め込みの API が無い提供元(Claude)なら null */
    fun cloudProfile(provider: ApiProvider): EmbeddingModelProfile? = when (provider) {
        ApiProvider.GEMINI -> CLOUD_GEMINI
        ApiProvider.OPENAI -> CLOUD_OPENAI
        ApiProvider.CLAUDE -> null
    }

    /** [provider] の埋め込みに使うモデルの ID。プロファイル(しきい値・版)と組なので、利用者には選ばせない */
    fun cloudModelId(provider: ApiProvider): String? = when (provider) {
        ApiProvider.GEMINI -> "gemini-embedding-2"
        ApiProvider.OPENAI -> "text-embedding-3-small"
        ApiProvider.CLAUDE -> null
    }
}

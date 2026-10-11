package com.unchunks.echomark.domain.provider

/**
 * 埋め込みモデルごとの性質としきい値。
 * モデルが変わるとベクトルの分布(類似度の出方)も変わるため、検索・チャットはここの値を読む。
 *
 * ObjectBox の索引([com.unchunks.echomark.data.local.objectbox.EmbeddingEntity])は 768 次元固定なので、
 * 現状 [dimensions] は 768 のモデルだけが使える(Matryoshka で 512/256/128 に切り詰める場合は索引の次元も変える必要がある)。
 */
data class EmbeddingModelProfile(
    /** 設定・メタデータに保存する安定した識別子(変更しない) */
    val id: String,
    /** 設定画面に出す名前 */
    val displayName: String,
    /** 保存したベクトルに付ける版。この値が違うベクトル同士は比較しない */
    val modelVersion: String,
    val dimensions: Int,
    /**
     * ハイブリッド検索でベクトル検索の結果を採用する距離の上限
     * (ObjectBox COSINE の距離 = 1 - コサイン類似度。小さいほど近い)。
     */
    val maxSearchDistance: Double,
    /** チャット(RAG)で文脈に採用する最小のコサイン類似度 */
    val minRagSimilarity: Double,
    /** モデルが読める最大トークン数。これを超える入力は黙って切り捨てられる */
    val maxInputTokens: Int,
    /** 埋め込みに含める本文の先頭の最大文字数([EmbeddingInputBuilder]) */
    val maxContentChars: Int,
    /** 1トークンあたりの文字数の見積もり。[EmbeddingInputBuilder] の文字数予算に使う */
    val charsPerToken: Double = DEFAULT_CHARS_PER_TOKEN,
    /** 取り込みで選べる実験的なモデルか */
    val experimental: Boolean = false
) {
    companion object {
        /**
         * 日本語は 1 文字 ≒ 0.5〜1 トークン(Gemma のトークナイザ)だが、英数字・記号・URL の混在や
         * 見積もりのぶれを見込み、1 文字 = 1 トークンとして予算を立てる(切り捨てを避ける側に倒す)。
         */
        const val DEFAULT_CHARS_PER_TOKEN = 1.0

        /** 同梱の EmbeddingGemma(308M, 768 次元, 2K トークン)。 */
        val GEMMA_V1 = EmbeddingModelProfile(
            id = "embedding-gemma-300m",
            displayName = "EmbeddingGemma 300M",
            modelVersion = "embedding-gemma-300m-mediapipe-v1",
            dimensions = 768,
            maxSearchDistance = 0.4,
            minRagSimilarity = 0.5,
            maxInputTokens = 2048,
            maxContentChars = 1_000
        )

        /**
         * EmbeddingGemma 2(768 次元, 8K トークン)。取り込み時に選ぶ実験的な項目。
         * TODO: 実機で評価(docs/embedding-evaluation.md)して maxSearchDistance / minRagSimilarity /
         *   maxContentChars を決める。いまの値は v1 を仮に流用している。
         *   MediaPipe の TextEmbedder が v2 を読めるかも未確認。
         */
        val GEMMA_V2_EXPERIMENTAL = EmbeddingModelProfile(
            id = "embedding-gemma-2",
            displayName = "EmbeddingGemma 2 (実験的)",
            modelVersion = "embedding-gemma-2-mediapipe-v1",
            dimensions = 768,
            maxSearchDistance = 0.4, // TODO: 評価して決める
            minRagSimilarity = 0.5, // TODO: 評価して決める
            maxInputTokens = 8192,
            maxContentChars = 4_000, // TODO: 速度と精度を見て決める
            experimental = true
        )

        /** アセット同梱のモデル(取り込み済みのモデルが無いときに使う)。 */
        val BUNDLED = GEMMA_V1

        /** 取り込み時に選べるプロファイル。ここに無いモデルは取り込めない。 */
        val IMPORTABLE: List<EmbeddingModelProfile> = listOf(GEMMA_V1, GEMMA_V2_EXPERIMENTAL)

        fun findById(id: String): EmbeddingModelProfile? = IMPORTABLE.firstOrNull { it.id == id }
    }
}

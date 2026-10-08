package com.unchunks.echomark.domain.provider

/** embed* はモデルが使えないとき [EmbeddingUnavailableException] を投げる。 */
interface EmbeddingProvider {
    /**
     * いま使っている埋め込みモデルの性質としきい値。取り込みなどで実行中に切り替わりうるため、
     * 呼ぶたびに読み直すこと(値を保持しない)。
     */
    val profile: EmbeddingModelProfile

    val dimensions: Int get() = profile.dimensions

    /** 保存するベクトルに付ける版。検索はこの版と同じベクトルだけを比べる。 */
    val modelVersion: String get() = profile.modelVersion

    suspend fun embedDocument(text: String): FloatArray // 保存(索引)用: passage:
    suspend fun embedQuery(text: String): FloatArray     // 検索(質問)用: query:
}

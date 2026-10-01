package com.unchunks.echomark.domain.provider

/** embed* はモデルが使えないとき [EmbeddingUnavailableException] を投げる。 */
interface EmbeddingProvider {
    val dimensions: Int
    val modelVersion: String
    suspend fun embedDocument(text: String): FloatArray // 保存(索引)用: passage:
    suspend fun embedQuery(text: String): FloatArray     // 検索(質問)用: query:
}

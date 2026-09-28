package com.unchunks.echomark.data.local.objectbox

import io.objectbox.Box
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ベクトル近傍検索の結果1件分。
 * score は ObjectBox が返す距離(COSINE)で、小さいほど類似している。
 */
data class VectorSearchResult(
    val bookmarkId: Long,
    val score: Double
)

/**
 * ObjectBox の [EmbeddingEntity] への直接アクセスをここに集約する。
 * ブロッキング呼び出しのため、呼び出し側で withContext(io) を使うこと。
 */
@Singleton
class VectorSearchDataSource @Inject constructor(
    private val embeddingBox: Box<EmbeddingEntity>
) {

    /** bookmarkId に対応する埋め込みを登録する。既にあれば上書きする。 */
    fun upsert(bookmarkId: Long, vector: FloatArray, modelVersion: String) {
        val existingId = findByBookmarkId(bookmarkId)?.id ?: 0
        embeddingBox.put(
            EmbeddingEntity(
                id = existingId,
                bookmarkId = bookmarkId,
                vector = vector,
                modelVersion = modelVersion
            )
        )
    }

    fun findByBookmarkId(bookmarkId: Long): EmbeddingEntity? {
        val query = embeddingBox.query(EmbeddingEntity_.bookmarkId.equal(bookmarkId)).build()
        return try {
            query.findFirst()
        } finally {
            query.close()
        }
    }

    fun getVector(bookmarkId: Long): FloatArray? = findByBookmarkId(bookmarkId)?.vector

    fun getModelVersion(bookmarkId: Long): String? = findByBookmarkId(bookmarkId)?.modelVersion

    /** vector に近い順で最大 limit 件の bookmarkId とスコアを返す。 */
    fun nearestNeighbors(vector: FloatArray, limit: Int): List<VectorSearchResult> {
        val query = embeddingBox.query(
            EmbeddingEntity_.vector.nearestNeighbors(vector, limit)
        ).build()
        return try {
            query.findWithScores().map { VectorSearchResult(it.get().bookmarkId, it.score) }
        } finally {
            query.close()
        }
    }

    /** bookmarkId に紐づく埋め込みを削除する。 */
    fun deleteByBookmarkId(bookmarkId: Long) {
        val query = embeddingBox.query(EmbeddingEntity_.bookmarkId.equal(bookmarkId)).build()
        try {
            query.remove()
        } finally {
            query.close()
        }
    }
}

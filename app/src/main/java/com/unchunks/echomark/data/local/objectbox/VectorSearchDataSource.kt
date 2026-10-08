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

    private companion object {
        /** 版の絞り込みが近傍探索の後に効く場合に備えて、多めに取る倍率 */
        const val OVERFETCH_FACTOR = 4
    }

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

    /**
     * vector に近い順で最大 limit 件の bookmarkId とスコアを返す。
     * 異なる埋め込みモデルのベクトルは比べられないため、[modelVersion] が一致する埋め込みだけを対象にする。
     *
     * ObjectBox が HNSW の近傍探索と他の条件をどの順で適用するか(探索中に絞るか、上位 N 件を取ってから絞るか)は
     * 保証されていない。後者だと、旧版のベクトルが近傍の上位を占めたときに結果が limit 件より減るため、
     * 多めに([OVERFETCH_FACTOR] 倍)取ってから版で絞り、近い順に limit 件へ切り詰める。
     * 全件が旧版(再埋め込みの途中)のときは空になる。
     */
    fun nearestNeighbors(vector: FloatArray, limit: Int, modelVersion: String): List<VectorSearchResult> {
        if (limit <= 0) return emptyList()
        val query = embeddingBox.query(
            EmbeddingEntity_.vector.nearestNeighbors(vector, limit * OVERFETCH_FACTOR)
                .and(EmbeddingEntity_.modelVersion.equal(modelVersion))
        ).build()
        return try {
            query.findWithScores()
                .map { VectorSearchResult(it.get().bookmarkId, it.score) }
                // 版の絞り込みが近傍探索の後に効く場合に備え、ここでも保証する(近い順に並べ直して切り詰める)
                .sortedBy { it.score }
                .take(limit)
        } finally {
            query.close()
        }
    }

    /** [modelVersion] で保存されている埋め込みの件数。 */
    fun countByModelVersion(modelVersion: String): Long {
        val query = embeddingBox.query(EmbeddingEntity_.modelVersion.equal(modelVersion)).build()
        return try {
            query.count()
        } finally {
            query.close()
        }
    }

    /** すべての埋め込みを削除する(全データ削除用)。 */
    fun deleteAll() {
        embeddingBox.removeAll()
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

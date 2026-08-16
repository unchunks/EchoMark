package com.unchunks.echomark.data.local.objectbox

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.VectorDistanceType

@Entity
data class EmbeddingEntity(
    @Id
    var id: Long = 0,
    var bookmarkId: Long = 0,
    @HnswIndex(dimensions = 768, distanceType = VectorDistanceType.COSINE)
    var vector: FloatArray = FloatArray(0),
    var modelVersion: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbeddingEntity

        if (id != other.id) return false
        if (bookmarkId != other.bookmarkId) return false
        if (!vector.contentEquals(other.vector)) return false
        if (modelVersion != other.modelVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + bookmarkId.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + modelVersion.hashCode()
        return result
    }
}

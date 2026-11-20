package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.unchunks.echomark.domain.model.TagSource

@Entity(
    tableName = "bookmark_tag_cross_ref",
    primaryKeys = ["bookmarkId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId")]
)
data class BookmarkTagCrossRef(
    val bookmarkId: Long,
    val tagId: Long,
    /**
     * 誰が付けたか(列は名前の文字列)。v8 以前の紐付けは誰が付けたか分からないため、
     * ユーザーのタグを誤って消さないよう USER にしている(MIGRATION_8_9)
     */
    val source: TagSource = TagSource.USER
)

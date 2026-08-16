package com.unchunks.echomark.data.local.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class BookmarkWithTags(
    @Embedded val bookmark: BookmarkEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = BookmarkTagCrossRef::class,
            parentColumn = "bookmarkId",
            entityColumn = "tagId"
        )
    )
    val tags: List<TagEntity>
)

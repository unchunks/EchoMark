package com.unchunks.echomark.data.local.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * 一覧に出すブックマーク([BookmarkWithTags] から本文を除いたもの)。[bookmark] の content は null で、
 * 本文の長さ(文字数。本文が無ければ null)を [contentLength] に持つ(読む時間の目安を求め直すかの判定に使う)。
 */
data class BookmarkListRow(
    @Embedded val bookmark: BookmarkEntity,
    val contentLength: Int?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = BookmarkTagCrossRef::class,
            parentColumn = "bookmarkId",
            entityColumn = "tagId"
        )
    )
    val tags: List<TagEntity>,
    /** タグの紐付けそのもの。誰が付けたか([BookmarkTagCrossRef.source])を見るために使う */
    @Relation(
        parentColumn = "id",
        entityColumn = "bookmarkId"
    )
    val tagRefs: List<BookmarkTagCrossRef>
)

/** ブックマークの本文だけ */
data class BookmarkContentRow(val id: Long, val content: String?)

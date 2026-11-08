package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ブックマークに付けた自分のノート(#95)。AI の要約([BookmarkEntity.summary])や本文([BookmarkEntity.content])とは
 * 別のテーブルに持ち、AI の再処理・本文の再取得・要約の手直しで書き換わらない。1件のブックマークに複数付けられる。
 * [quote] は本文などから引用した文章(注釈)で、無ければ単なるノート。ブックマークを削除すると連鎖して消える。
 * バックアップに含める。
 */
@Entity(
    tableName = "bookmark_notes",
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookmarkId")]
)
data class BookmarkNoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val bookmarkId: Long,

    /** ノートの文章 */
    val text: String,

    /** 引用した文章(注釈の対象)。引用が無ければ null */
    val quote: String? = null,

    val createdAt: Long,

    /** 最後に直した日時(直していなければ [createdAt] と同じ) */
    val updatedAt: Long
)

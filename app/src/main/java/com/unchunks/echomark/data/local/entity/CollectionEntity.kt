package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * コレクション(#91)。自分で選んだブックマークを集めて名前を付けたもの。条件で探し直すスマートフォルダ(#34)や、
 * 内容から付くタグと違い、入れたブックマークだけが入る。1件のブックマークを複数のコレクションに入れられる。
 * 名前は大文字小文字・全角半角を区別せず1つだけ。バックアップに含める。
 */
@Entity(
    tableName = "collections",
    indices = [Index(value = ["name"], unique = true)]
)
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    /** コレクションの説明(書き出すときの冒頭に入る)。無ければ null */
    val description: String? = null,

    val createdAt: Long
)

/**
 * コレクションに入っているブックマーク。コレクションかブックマークを削除すると連鎖して消える。
 * 並びは入れた順([addedAt])。
 */
@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "bookmarkId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookmarkId")]
)
data class CollectionItemEntity(
    val collectionId: Long,
    val bookmarkId: Long,
    /** コレクションに入れた日時 */
    val addedAt: Long
)

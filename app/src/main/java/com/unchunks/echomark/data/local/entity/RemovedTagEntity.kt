package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * ユーザーが外したタグの記録。AI の再処理で、同じブックマークにそのタグをまた付けないために使う。
 * タグの ID ではなくキー(TagNames.key)で覚える: 外したタグは件数 0 で削除されたり、統合で別の ID になったりするため。
 * ユーザーが付け直せば記録は消える。ブックマークを削除すると記録も消える(外部キーの連鎖)。
 */
@Entity(
    tableName = "removed_tags",
    primaryKeys = ["bookmarkId", "tagKey"],
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RemovedTagEntity(
    val bookmarkId: Long,

    /** 外したタグの名前のキー(TagNames.key) */
    val tagKey: String,

    /** 外したタグの名前そのまま */
    val tagName: String,

    val removedAt: Long
)

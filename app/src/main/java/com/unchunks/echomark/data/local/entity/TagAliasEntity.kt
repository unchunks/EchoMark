package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * タグの別名。タグを統合・名前変更したときの元の名前を覚えておき、AI がその名前を出しても、新しいタグを作り直さず
 * [tagId] のタグに付ける(「ネコ」を「猫」に統合したあと、AI が「ネコ」と出しても「猫」になる)。
 * タグを削除すると別名も消える(外部キーの連鎖)。
 */
@Entity(
    tableName = "tag_aliases",
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId")]
)
data class TagAliasEntity(
    /** 別名を同じとみなすためのキー(TagNames.key。全角/半角・大文字/小文字を区別しない)。同じキーの別名は1つだけ */
    @PrimaryKey
    val aliasKey: String,

    /** 別名そのままの表記 */
    val alias: String,

    val tagId: Long
)

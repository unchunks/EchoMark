package com.unchunks.echomark.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    /**
     * ユーザーのタグか(ユーザーが付けた・名前を変更した・統合先にした)。
     * false のタグは AI だけが付けたもので、どのブックマークにも付かなくなったら削除する(TagDao.deleteOrphanAiTags)。
     * ユーザーが付けた紐付け(TagSource.USER)があるタグは必ず true にする
     */
    @ColumnInfo(defaultValue = "0")
    val isUserCreated: Boolean = false
)

package com.unchunks.echomark.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tags",
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["name"], unique = true), Index("parentId")]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    /**
     * ユーザーのタグか(ユーザーが付けた・名前を変更した・統合先にした・階層に組み込んだ)。
     * false のタグは AI だけが付けたもので、どのブックマークにも付かなくなったら削除する(TagDao.deleteOrphanAiTags)。
     * ユーザーが付けた紐付け(TagSource.USER)があるタグは必ず true にする
     */
    @ColumnInfo(defaultValue = "0")
    val isUserCreated: Boolean = false,

    /**
     * 親のタグ(階層。null なら最上位)。親を削除すると、子は削除した親の親につく(TagDao.deleteTagWithRefs。無ければ最上位。SET_NULL は念のため)。
     * 輪にならない(自分や自分の子孫を親にしない)ことは TagDao.setParent が守る
     */
    val parentId: Long? = null
)

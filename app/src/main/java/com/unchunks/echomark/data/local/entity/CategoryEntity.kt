package com.unchunks.echomark.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ユーザーが作ったカテゴリ(#33)。ブックマークのカテゴリ(bookmarks.category)は名前の文字列で持つため、
 * ここは「選べるカテゴリの一覧」と、AI が分類に使う説明を持つ。標準のカテゴリ(技術・ニュースなど)は行にせず、コードで持つ。
 * 名前は大文字小文字・全角半角を区別せず1つだけ(CategoryDao・CategoryRepositoryImpl が確かめる)。
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    /** どんなブックマークを入れるカテゴリか。AI が分類に使い、画面にも出す(空でもよい) */
    @ColumnInfo(defaultValue = "''")
    val description: String = "",

    val createdAt: Long
)

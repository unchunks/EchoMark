package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * スマートフォルダ(#34)。保存した絞り込み条件([com.unchunks.echomark.domain.bookmark.model.BookmarkQuery])に名前を付けたもの。
 * 開くたびに条件で探し直すので、期間(「1か月以内」)や読んだか(未読)も、そのときの状態で変わる。
 *
 * 条件の値は文字列で持つ(新しい版で増えた値や、消えた値を読んでも、その項目だけ既定に戻せるようにするため)。
 * 複数の値は、タグの ID(数値)と種類(列挙の名前)はコンマ、カテゴリの名前は改行で区切る(`SmartFolderCodec`)。
 * タグは ID で持つので、タグを削除すると条件から外れたものとして扱う(一覧の絞り込みと同じ)。名前は大文字小文字・全角半角を区別せず1つだけ。
 */
@Entity(
    tableName = "smart_folders",
    indices = [Index(value = ["name"], unique = true)]
)
data class SmartFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    /** 通常 / アーカイブ / お気に入り([com.unchunks.echomark.domain.bookmark.model.BookmarkFilter] の名前) */
    val filter: String,

    /** 選んだタグの ID(コンマ区切り)。空なら絞らない */
    val tagIds: String,

    /** タグの組み合わせ方([com.unchunks.echomark.domain.bookmark.model.TagMatch] の名前) */
    val tagMatch: String,

    /** 選んだカテゴリの名前(改行区切り)。空なら絞らない */
    val categories: String,

    /** 選んだ種類([com.unchunks.echomark.domain.bookmark.model.BookmarkType] の名前、コンマ区切り)。空なら絞らない */
    val types: String,

    /** 保存した日からの期間([com.unchunks.echomark.domain.bookmark.model.CreatedPeriod] の名前)。null なら絞らない */
    val period: String?,

    /** 読んだか([com.unchunks.echomark.domain.bookmark.model.ReadState] の名前)。null なら絞らない */
    val readState: String?,

    /** 開いたときの並び順([com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder] の名前)。null なら今の並び順のまま */
    val sortOrder: String?,

    val createdAt: Long
)

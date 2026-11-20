package com.unchunks.echomark.domain.bookmark.model

import com.unchunks.echomark.domain.model.TagNames

/**
 * コレクション(#91)。自分で選んだブックマークを集めて名前を付けたもの。
 * 条件で探し直すスマートフォルダ([SmartFolder])とは違い、入れたものだけが入る。
 * @property bookmarkCount 入れているブックマークの数
 */
data class BookmarkCollection(
    val id: Long,
    val name: String,
    val description: String? = null,
    val bookmarkCount: Int = 0,
    val createdAt: Long = 0L
)

/** コレクションに入れたブックマークの記録([bookmarkId] と、入れた日時)。削除の「元に戻す」で使う。 */
data class CollectionMember(val bookmarkId: Long, val addedAt: Long)

/** 削除したコレクションの中身(名前・説明・入れていたブックマーク)。「元に戻す」で同じ ID で作り直す。 */
data class DeletedCollection(
    val id: Long,
    val name: String,
    val description: String?,
    val createdAt: Long,
    val members: List<CollectionMember>
)

/** コレクションの名前・説明の決まり。純粋関数(JVM 単体テスト可能)。 */
object CollectionNames {
    const val MAX_CHARS = 40
    const val MAX_DESCRIPTION_CHARS = 300

    /** 前後の空白を除き、語の間の改行を空白にした名前。空・長すぎるときは null */
    fun normalize(name: String): String? =
        name.replace(Regex("[\r\n]+"), " ").trim().takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }

    /** 前後の空白を除いた説明。空なら null。長すぎれば [MAX_DESCRIPTION_CHARS] で切る */
    fun cleanDescription(description: String?): String? =
        description?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DESCRIPTION_CHARS)

    /** 同じ名前とみなすためのキー(大文字小文字・全角半角を区別しない) */
    fun key(name: String): String = TagNames.key(name)
}

/** コレクションを作る・直すときの結果。 */
sealed interface CollectionResult {
    /** 保存した。[id] はそのコレクション */
    data class Saved(val id: Long) : CollectionResult

    /** 名前が空か、長すぎる */
    data object InvalidName : CollectionResult

    /** 同じ名前のコレクションが既にある */
    data object NameTaken : CollectionResult

    /** 直そうとしたコレクションが無い(削除済み) */
    data object NotFound : CollectionResult
}

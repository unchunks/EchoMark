package com.unchunks.echomark.domain.bookmark.model

import com.unchunks.echomark.domain.model.TagNames

/**
 * スマートフォルダ(#34)。名前を付けて保存した絞り込み条件。開くたびに条件で探し直すので、
 * 相対の期間([CreatedPeriod])や読んだか([ReadState])は、そのときの状態で中身が変わる。
 * @property sortOrder 開いたときの並び順。null なら今の並び順のまま
 */
data class SmartFolder(
    val id: Long,
    val name: String,
    val query: BookmarkQuery,
    val sortOrder: BookmarkSortOrder? = null,
    val createdAt: Long = 0L
)

/** スマートフォルダの名前の決まり。純粋関数(JVM 単体テスト可能)。 */
object SmartFolderNames {
    const val MAX_CHARS = 30

    /** 前後の空白を除き、語の間の改行を空白にした名前。空・長すぎるときは null */
    fun normalize(name: String): String? =
        name.replace(Regex("[\r\n]+"), " ").trim().takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }

    /** 同じ名前とみなすためのキー(大文字小文字・全角半角を区別しない) */
    fun key(name: String): String = TagNames.key(name)
}

/** スマートフォルダを作る・名前を変えるときの結果。 */
sealed interface SmartFolderResult {
    /** 保存した。[id] はそのフォルダ */
    data class Saved(val id: Long) : SmartFolderResult

    /** 名前が空か、長すぎる */
    data object InvalidName : SmartFolderResult

    /** 同じ名前のフォルダが既にある */
    data object NameTaken : SmartFolderResult
}

/** 保存した条件で、今の一覧を探し直しているか(条件が同じか)。並び順は見ない。 */
fun SmartFolder.matches(query: BookmarkQuery): Boolean = this.query == query

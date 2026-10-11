package com.unchunks.echomark.domain.model

data class Tag(
    val id: Long,
    val name: String
)

/**
 * タグと、そのタグが付いたブックマークの件数。
 * @param isUserTag ユーザーが付けた・名前を付け直したタグか。false なら AI だけが付けたタグ
 *   (どのブックマークにも付かなくなると自動で消える)
 */
data class TagWithCount(
    val id: Long,
    val name: String,
    val bookmarkCount: Int,
    val isUserTag: Boolean = true
)

/** ブックマークにタグを付けたのは誰か(紐付けごとに記録する)。 */
enum class TagSource {
    /** ユーザーが付けた(詳細画面・共有シートなど)。AI の再処理では外さない */
    USER,

    /** AI が付けた。再処理のたびに付け直す */
    AI
}

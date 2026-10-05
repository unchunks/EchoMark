package com.unchunks.echomark.domain.bookmark.model

// ここを変更するときは、com.unchunks.echomark.data.local.entity.BookmarkEntity.kt の変更が不要か確認すること
data class Bookmark(
    val id: Long = 0,
    val type: BookmarkType,
    val content: String? = null,
    val contentUri: String? = null,
    val title: String,
    val summary: String? = null,
    val category: String? = null,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val aiStatus: AiStatus = AiStatus.PENDING,
    val tags: List<String> = emptyList(),
    /** [tags] のうち AI が付けたもの(残りはユーザーが付けたもの)。再処理すると付け直される */
    val aiTags: Set<String> = emptySet(),
    /** リンク先の OG 画像 URL(取得できなければ null) */
    val imageUrl: String? = null,
    /** リンク先のサイト名(og:site_name など。取得できなければ null) */
    val siteName: String? = null,
    val isFavorite: Boolean = false,
    /** アーカイブ済み(通常の一覧には出さない) */
    val isArchived: Boolean = false
)

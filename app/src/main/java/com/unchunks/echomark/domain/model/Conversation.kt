package com.unchunks.echomark.domain.model

data class Conversation(
    val id: Long = 0,
    val title: String,
    val isTitleManuallySet: Boolean = false,
    val summary: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** 「このブックマークについて質問」の対象。通常の会話は null(削除済みのブックマークを指すこともある)。 */
    val aboutBookmarkId: Long? = null,
    /** [summary] に含めた最後の発言の日時。null なら要約はまだ無い。 */
    val summaryCoveredUntil: Long? = null,
    /** 一覧の先頭に固定しているか。 */
    val isPinned: Boolean = false,
    /** この会話を分けた元の会話。分けていなければ null(元が削除済みのこともある)。 */
    val branchedFromId: Long? = null,
    /** 元の会話と共通の最後の発言の日時。 */
    val branchedAt: Long? = null
)

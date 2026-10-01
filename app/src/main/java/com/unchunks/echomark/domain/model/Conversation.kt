package com.unchunks.echomark.domain.model

data class Conversation(
    val id: Long = 0,
    val title: String,
    val isTitleManuallySet: Boolean = false,
    val summary: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** 「このブックマークについて質問」の対象。通常の会話は null(削除済みのブックマークを指すこともある)。 */
    val aboutBookmarkId: Long? = null
)

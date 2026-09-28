package com.unchunks.echomark.domain.model

data class Conversation(
    val id: Long = 0,
    val title: String,
    val isTitleManuallySet: Boolean = false,
    val summary: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

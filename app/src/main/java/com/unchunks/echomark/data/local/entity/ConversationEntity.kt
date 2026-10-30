package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,
    val isTitleManuallySet: Boolean = false,
    /** 古い発言を要約した、会話の記憶(端末内 AI の文脈に入れる)。[summaryCoveredUntil] までの発言をまとめている */
    val summary: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * 「このブックマークについて質問」で始めた会話の対象ブックマーク。通常の会話は null。
     * ブックマークの削除を取り消したときに紐付けが戻るよう、外部キーにはしない(削除済みなら通常の会話として扱う)。
     */
    val aboutBookmarkId: Long? = null,
    /**
     * [summary] に含めた最後の発言の日時(createdAt)。null なら要約はまだ無い。
     * バックアップの読み込みで発言の ID は振り直されるため、ID ではなく日時で持つ。
     */
    val summaryCoveredUntil: Long? = null
)

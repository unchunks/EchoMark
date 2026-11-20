package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * クラウド API の1回の呼び出しで使ったトークン数(#69)。提供元・用途は enum の名前で持つ
 * (読めない値は読み出しで飛ばす)。バックアップには含めない。
 */
@Entity(tableName = "api_usage", indices = [Index("createdAt")])
data class ApiUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val createdAt: Long,
    val provider: String,
    val model: String,
    val task: String,
    val inputTokens: Long,
    val outputTokens: Long
)

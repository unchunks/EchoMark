package com.unchunks.echomark.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage

// ここを変更するときは、ccom.unchunks.echomark.domain.bookmark.model.Bookmark.kt の変更が不要か確認すること
// contentUri に unique index を張り、同一URLの重複保存を防ぐ(NULLは複数可)
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["contentUri"], unique = true)]
)
data class BookmarkEntity (
    @PrimaryKey(autoGenerate = true)
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
    // v7 で追加。ALTER TABLE で足した列とスキーマを一致させるため、既定値を明示する
    val imageUrl: String? = null,
    val siteName: String? = null,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isArchived: Boolean = false,
    // v10 で追加。保存したファイル(画像・PDF・音声など)の情報
    val filePath: String? = null,
    val mimeType: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    // v11 で追加。URL の本文(リンク先)を取得できた日時。未取得・取得に失敗したままなら null
    val contentFetchedAt: Long? = null,
    // v12 で追加。処理の段・日時・失敗の理由(ProcessingInfo)。端末の中の一時的な状態のため、バックアップには含めない
    val processingStage: ProcessingStage? = null,
    val queuedAt: Long? = null,
    val processingStartedAt: Long? = null,
    val stageStartedAt: Long? = null,
    val processingFinishedAt: Long? = null,
    val failureReason: ProcessingFailureReason? = null,
    // v15 で追加。要約をユーザーが手で直したもの(再処理しても要約は置き換えない。#19)
    @ColumnInfo(defaultValue = "0")
    val summaryEdited: Boolean = false,
    // v18 で追加。カテゴリをユーザーが選んだもの(再処理しても AI はカテゴリを置き換えない。#33)
    @ColumnInfo(defaultValue = "0")
    val categoryEdited: Boolean = false
)

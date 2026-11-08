package com.unchunks.echomark.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 購読しているフィード(RSS・Atom・YouTube のチャンネル。#78)。夜間に新しい記事・動画を確かめて、ブックマークとして保存する。
 *
 * [feedUrl] は1つだけ(同じフィードを2回購読しない)。
 * バックアップに入れるのは [type]・[feedUrl]・[title]・[siteUrl]・[createdAt]・[enabled] だけで、
 * 確認の状態([lastCheckedAt] 以降の列)は端末の中のものなので含めない(読み込んだ端末では、最初の確認から始める)。
 */
@Entity(
    tableName = "subscriptions",
    indices = [Index(value = ["feedUrl"], unique = true)]
)
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** フィードの種類([com.unchunks.echomark.domain.subscription.SubscriptionType] の名前) */
    val type: String,

    /** フィードの URL(http / https) */
    val feedUrl: String,

    /** フィードのタイトル(保存したブックマークに付けるタグの名前にもなる) */
    val title: String,

    /** フィードのサイトの URL。分からなければ null */
    val siteUrl: String? = null,

    val createdAt: Long,

    /** オフにすると確認しない(購読は残る) */
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,

    // ---- 確認の状態(端末の中のもの。バックアップに含めない) ----

    /** 最後に確認した日時(結果によらず)。null ならまだ一度も確認していない */
    val lastCheckedAt: Long? = null,

    /** 最後に確認できた(取得して読めた)日時 */
    val lastSuccessAt: Long? = null,

    /** 条件付きの取得に使う ETag(変わっていなければ本文を取らない) */
    val etag: String? = null,

    /** 条件付きの取得に使う Last-Modified */
    val lastModified: String? = null,

    /** 続けて失敗した回数(成功したら 0) */
    @ColumnInfo(defaultValue = "0")
    val failureCount: Int = 0,

    /** 最後の失敗の理由(利用者に見せる文)。成功したら null */
    val lastError: String? = null,

    /** すでに見た記事の目印(記事の ID または URL を改行で区切ったもの。新しいものが後ろ。古いものは切り捨てる) */
    @ColumnInfo(defaultValue = "''")
    val seenKeys: String = ""
)

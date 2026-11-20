package com.unchunks.echomark.data.local.entity

import com.unchunks.echomark.domain.bookmark.model.BookmarkType

/**
 * ブックマークの、タイトルと出どころ(サイト名・ドメイン・ファイルの種類)を出すのに要る列だけ。
 * 本文・要約・タグは読まない(全件を何度も読み直す問い合わせで、本文まで読むと重いため)。
 */
data class BookmarkSourceRow(
    val id: Long,
    val type: BookmarkType,
    val title: String,
    val contentUri: String?,
    val siteName: String?,
    val filePath: String?,
    val mimeType: String?,
    val fileSize: Long?,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val isArchived: Boolean
)

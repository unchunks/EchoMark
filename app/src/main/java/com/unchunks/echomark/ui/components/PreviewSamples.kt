package com.unchunks.echomark.ui.components

import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType

/**
 * @Preview とスクリーンショットテストで共有するサンプルデータ。
 * 相対日時が実行日で変わらないよう、基準時刻 [NOW] を固定している。
 */
internal object PreviewSamples {
    /** 2026-10-01 09:00 (JST) */
    const val NOW: Long = 1_790_812_800_000L
    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR

    val urlBookmark = Bookmark(
        id = 1,
        type = BookmarkType.URL,
        contentUri = "https://www.example.com/articles/compose-performance",
        title = "Jetpack Compose のパフォーマンスを改善する 7 つのヒント",
        summary = "再コンポーズを減らすための安定性の考え方、remember と derivedStateOf の使い分け、" +
            "Lazy リストのキー指定など、実践的な改善手順をまとめた記事。",
        createdAt = NOW - 3 * DAY,
        lastAccessedAt = NOW - 3 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("Android", "Compose", "パフォーマンス", "Kotlin"),
        imageUrl = "https://www.example.com/og/compose.png",
        siteName = "Example Tech Blog",
        isFavorite = true
    )

    val processingBookmark = Bookmark(
        id = 2,
        type = BookmarkType.URL,
        contentUri = "https://developer.android.com/topic/architecture",
        title = "https://developer.android.com/topic/architecture",
        createdAt = NOW - 5 * 60 * 1000L,
        lastAccessedAt = NOW - 5 * 60 * 1000L,
        aiStatus = AiStatus.PROCESSING
    )

    val textBookmark = Bookmark(
        id = 3,
        type = BookmarkType.TEXT,
        title = "読書メモ: 『知の編集術』",
        content = "情報は編集することで知識になる。",
        summary = "情報を「編集」することで自分の知識に変えるという考え方のメモ。",
        createdAt = NOW - 40 * DAY,
        lastAccessedAt = NOW - 10 * DAY,
        aiStatus = AiStatus.FAILED,
        tags = listOf("読書")
    )

    val waitingModelBookmark = Bookmark(
        id = 4,
        type = BookmarkType.PDF,
        title = "2026年度 研究計画書.pdf",
        createdAt = NOW - 2 * HOUR,
        lastAccessedAt = NOW - 2 * HOUR,
        aiStatus = AiStatus.WAITING_MODEL
    )

    val all = listOf(urlBookmark, processingBookmark, textBookmark, waitingModelBookmark)
}

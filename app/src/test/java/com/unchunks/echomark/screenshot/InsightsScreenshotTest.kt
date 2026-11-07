package com.unchunks.echomark.screenshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.insights.InterestTrends
import com.unchunks.echomark.domain.insights.LibraryStatsCalculator
import com.unchunks.echomark.domain.insights.TagCloud
import com.unchunks.echomark.domain.insights.Timeline
import com.unchunks.echomark.domain.insights.TimelineBasis
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.insights.InsightsContent
import com.unchunks.echomark.ui.insights.InsightsTab
import com.unchunks.echomark.ui.insights.InsightsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** ライブラリの分析の見た目(タグクラウド・統計・タイムライン・関心の変化・空)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightsScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun daysAgo(days: Long) = now - TimeUnit.DAYS.toMillis(days)

    private val topics = listOf(
        listOf("Android", "Compose"), listOf("Kotlin", "Android"), listOf("料理"), listOf("旅行", "写真"),
        listOf("Rust"), listOf("Kotlin"), listOf("Compose", "Kotlin")
    )

    /** 直近の半年に、少しずつ話題が移っていくサンプル */
    private val bookmarks: List<Bookmark> = (1..60).map { i ->
        val age = (i * 3L) % 170
        Bookmark(
            id = i.toLong(),
            type = if (i % 5 == 0) BookmarkType.PDF else if (i % 7 == 0) BookmarkType.TEXT else BookmarkType.URL,
            title = "サンプルのブックマーク $i",
            createdAt = daysAgo(age),
            lastAccessedAt = daysAgo(age / 2),
            readAt = if (i % 3 == 0) daysAgo(age / 2) else null,
            category = listOf("技術", "料理", "旅行", null)[i % 4],
            tags = if (age < 85) topics[(i * 2) % topics.size] else topics[i % 4],
            isFavorite = i % 9 == 0,
            summary = if (i % 2 == 0) "要約" else null
        )
    }

    private val tags = listOf(
        TagWithCount(1, "Android", 18), TagWithCount(2, "Kotlin", 24), TagWithCount(3, "Compose", 11),
        TagWithCount(4, "料理", 6), TagWithCount(5, "旅行", 4), TagWithCount(6, "写真", 2),
        TagWithCount(7, "Rust", 3, isUserTag = false), TagWithCount(8, "読書", 1, isUserTag = false)
    )

    private val state = InsightsUiState(
        isLoading = false,
        bookmarkCount = bookmarks.size,
        tagCloud = TagCloud.build(tags),
        stats = LibraryStatsCalculator.compute(bookmarks, now, zone),
        timeline = Timeline.rows(bookmarks.take(12), TimelineBasis.SAVED, zone),
        interests = InterestTrends.compute(bookmarks, now, zone)
    )

    private fun content(state: InsightsUiState, tab: InsightsTab): @androidx.compose.runtime.Composable () -> Unit = {
        InsightsContent(
            uiState = state,
            onBack = {},
            onOpenTag = {},
            onOpenBookmark = {},
            onTimelineBasis = {},
            onInterestMonths = {},
            initialTab = tab
        )
    }

    @Test
    fun cloud() = screenshot.captureScreenLightDark("insights_cloud", content = content(state, InsightsTab.CLOUD))

    @Test
    fun stats() = screenshot.captureScreenLightDark("insights_stats", content = content(state, InsightsTab.STATS))

    @Test
    fun timeline() = screenshot.captureScreenLightDark("insights_timeline", content = content(state, InsightsTab.TIMELINE))

    @Test
    fun interests() = screenshot.captureScreenLightDark("insights_interests", content = content(state, InsightsTab.INTERESTS))

    @Test
    fun empty() = screenshot.captureScreenLightDark(
        "insights_empty",
        content = content(InsightsUiState(isLoading = false), InsightsTab.CLOUD)
    )
}

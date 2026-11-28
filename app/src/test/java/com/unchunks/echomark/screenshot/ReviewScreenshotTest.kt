package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.review.ReadingList
import com.unchunks.echomark.domain.review.ReviewCard
import com.unchunks.echomark.domain.review.ReviewGrade
import com.unchunks.echomark.domain.review.SpacedRepetition
import com.unchunks.echomark.domain.review.WeeklyReviewCalculator
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.review.ReviewCardItem
import com.unchunks.echomark.ui.review.ReviewHubActions
import com.unchunks.echomark.ui.review.ReviewHubContent
import com.unchunks.echomark.ui.review.ReviewHubTab
import com.unchunks.echomark.ui.review.ReviewHubUiState
import com.unchunks.echomark.ui.review.ReviewItem
import com.unchunks.echomark.ui.review.ReviewSessionContent
import com.unchunks.echomark.ui.review.ReviewSessionUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 読む・ふり返る(今週のふり返り・あとで読む・復習)と復習の画面の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW
    private val day = TimeUnit.DAYS.toMillis(1)
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun sample(id: Long, title: String, savedDaysAgo: Double, readDaysAgo: Double? = null, listedDaysAgo: Double? = null, tags: List<String> = emptyList()) =
        PreviewSamples.urlBookmark.copy(
            id = id, title = title, createdAt = now - (savedDaysAgo * day).toLong(), readAt = readDaysAgo?.let { now - (it * day).toLong() },
            readingListAt = listedDaysAgo?.let { now - (it * day).toLong() }, tags = tags, aiTags = emptySet(),
            content = "あ".repeat(2_000), isFavorite = false
        )

    private val bookmarks: List<Bookmark> = listOf(
        sample(1, "Jetpack Compose のパフォーマンスを改善する 7 つのヒント", 1.0, tags = listOf("Android", "Compose")),
        sample(2, "Kotlin コルーチンの構造化された並行性", 2.5, readDaysAgo = 1.0, tags = listOf("Kotlin")),
        sample(3, "読書メモ: 『知の編集術』", 4.0, tags = listOf("読書")),
        sample(4, "京都の紅葉の名所まとめ", 20.0, listedDaysAgo = 3.0, tags = listOf("旅行")),
        sample(5, "家庭でできる和食のコツ", 40.0, listedDaysAgo = 1.0, tags = listOf("料理")),
        sample(6, "Rust 入門: 所有権を理解する", 30.0, tags = listOf("Rust")),
        sample(7, "古いまま読めていない記事", 60.0)
    )

    private fun card(id: Long, dueInDays: Double, interval: Int, reps: Int, lapses: Int = 0) =
        SpacedRepetition.newCard(id, now, zone).copy(dueAt = now + (dueInDays * day).toLong(), intervalDays = interval, repetitions = reps, lapses = lapses)

    private val cards: List<ReviewCard> = listOf(card(2, -0.5, 8, 3), card(1, 2.0, 3, 2, lapses = 1), card(6, 20.0, 40, 6))

    private val hubState = ReviewHubUiState(
        isLoading = false,
        weekly = WeeklyReviewCalculator.compute(bookmarks, now, dueReviews = 1),
        readingList = ReadingList.of(bookmarks),
        reviewCards = cards.mapNotNull { c -> bookmarks.firstOrNull { it.id == c.bookmarkId }?.let { ReviewCardItem(c, it, c.dueAt <= now) } },
        dueCount = 1
    )

    private fun hub(state: ReviewHubUiState, tab: ReviewHubTab): @androidx.compose.runtime.Composable () -> Unit = {
        ReviewHubContent(
            uiState = state, snackbarHostState = SnackbarHostState(), actions = ReviewHubActions(), initialTab = tab,
            nowMillis = now, zone = zone
        )
    }

    @Test
    fun weekly() = screenshot.captureScreenLightDark("review_weekly", content = hub(hubState, ReviewHubTab.WEEKLY))

    @Test
    fun weeklyQuiet() = screenshot.captureScreenLightDark(
        "review_weekly_quiet",
        content = hub(hubState.copy(weekly = WeeklyReviewCalculator.compute(emptyList(), now)), ReviewHubTab.WEEKLY)
    )

    @Test
    fun readingList() = screenshot.captureScreenLightDark("review_reading_list", content = hub(hubState, ReviewHubTab.READING_LIST))

    @Test
    fun readingListEmpty() = screenshot.captureScreenLightDark(
        "review_reading_list_empty", content = hub(hubState.copy(readingList = ReadingList.EMPTY), ReviewHubTab.READING_LIST)
    )

    @Test
    fun review() = screenshot.captureScreenLightDark("review_cards", content = hub(hubState, ReviewHubTab.REVIEW))

    @Test
    fun reviewEmpty() = screenshot.captureScreenLightDark(
        "review_cards_empty", content = hub(hubState.copy(reviewCards = emptyList(), dueCount = 0), ReviewHubTab.REVIEW)
    )

    // ---- 復習 ----

    private fun sessionState(showAnswer: Boolean): ReviewSessionUiState {
        val bookmark = bookmarks[1].copy(summary = "launch と async の使い分け、構造化された並行性の考え方、キャンセルの伝わり方をまとめた記事。")
        val item = ReviewItem(cards[0], bookmark)
        return ReviewSessionUiState(
            isLoading = false, queue = listOf(item, ReviewItem(cards[1], bookmarks[0])), showAnswer = showAnswer, total = 3, answered = 1,
            intervalLabels = ReviewGrade.entries.associateWith { SpacedRepetition.intervalLabel(SpacedRepetition.next(cards[0], it, now, zone)) }
        )
    }

    private fun session(state: ReviewSessionUiState): @androidx.compose.runtime.Composable () -> Unit = {
        ReviewSessionContent(uiState = state, onBack = {}, onOpenBookmark = {}, onReveal = {}, onAnswer = {})
    }

    @Test
    fun sessionFront() = screenshot.captureScreenLightDark("review_session_front", content = session(sessionState(false)))

    @Test
    fun sessionBack() = screenshot.captureScreenLightDark("review_session_back", content = session(sessionState(true)))

    @Test
    fun sessionFinished() = screenshot.captureScreenLightDark(
        "review_session_finished", content = session(ReviewSessionUiState(isLoading = false, answered = 5, total = 5))
    )

    @Test
    fun sessionNothingDue() = screenshot.captureScreenLightDark(
        "review_session_nothing", content = session(ReviewSessionUiState(isLoading = false))
    )
}

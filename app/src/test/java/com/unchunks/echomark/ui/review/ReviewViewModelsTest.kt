package com.unchunks.echomark.ui.review

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.review.ReviewGrade
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeReadingListRepository
import com.unchunks.echomark.testing.FakeReviewRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelsTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_000 * day

    private val bookmarks = FakeBookmarkRepository()
    private val readingList = FakeReadingListRepository()
    private val review = FakeReviewRepository().also { it.now = now }

    private fun bookmark(
        id: Long, daysAgo: Double = 30.0, readAt: Long? = null, listedAt: Long? = null, summary: String? = null
    ) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id", createdAt = now - (daysAgo * day).toLong(), lastAccessedAt = 0,
        readAt = readAt, readingListAt = listedAt, summary = summary
    )

    private fun TestScope.hub(): ReviewHubViewModel {
        val vm = ReviewHubViewModel(bookmarks, readingList, review, TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)))
        vm.clock = { now }
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    private fun TestScope.session(): ReviewSessionViewModel =
        ReviewSessionViewModel(review, bookmarks).also { it.clock = { now } }

    private fun dueCard(id: Long) = com.unchunks.echomark.domain.review.SpacedRepetition.newCard(id, now - 2 * day, review.zone)
        .copy(dueAt = now - 1)

    // ---- ふり返りの画面 ----

    @Test
    fun ふり返りの画面は_今週のふり返りとあとで読むと復習をまとめて出す() = runTest {
        bookmarks.bookmarks.value = listOf(
            bookmark(1, daysAgo = 1.0), bookmark(2, listedAt = now - day), bookmark(3, listedAt = now - 2 * day), bookmark(4)
        )
        review.cards.value = listOf(dueCard(4), dueCard(999)) // 999 のブックマークは無い
        val vm = hub()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.weekly!!.savedCount)
        // 日時の来た復習は、ブックマークがあるものだけ数える
        assertEquals(1, state.dueCount)
        assertEquals(1, state.weekly!!.dueReviews)
        // あとで読むは入れた順(古いものが先)
        assertEquals(listOf(3L, 2L), state.readingList.items.map { it.id })
        assertEquals(listOf(4L), state.reviewCards.map { it.bookmark.id })
        assertTrue(state.reviewCards.single().due)
    }

    @Test
    fun まだ日時が来ていないカードは_復習できる数に入れない() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1))
        review.cards.value = listOf(dueCard(1).copy(dueAt = now + day))
        val vm = hub()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.dueCount)
        assertEquals(1, vm.uiState.value.reviewCards.size)
        assertFalse(vm.uiState.value.reviewCards.single().due)
    }

    @Test
    fun あとで読むから外す_読んだことにする_復習から外す() = runTest {
        val target = bookmark(1, listedAt = now - day)
        bookmarks.bookmarks.value = listOf(target)
        readingList.ids += 1
        review.cards.value = listOf(dueCard(1))
        val vm = hub()
        val messages = mutableListOf<ReviewHubMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.toList(messages) }
        advanceUntilIdle()

        vm.removeFromReadingList(target)
        advanceUntilIdle()
        assertTrue(readingList.ids.isEmpty())
        assertEquals(ReviewHubMessage.RemovedFromReadingList("題1", 1), messages.last())

        vm.undoRemoveFromReadingList(1)
        advanceUntilIdle()
        assertEquals(listOf(1L), readingList.ids)

        vm.markRead(target)
        advanceUntilIdle()
        assertNotNull(bookmarks.bookmarks.value.single().readAt)
        assertEquals(ReviewHubMessage.MarkedRead("題1"), messages.last())

        vm.removeFromReview(target)
        advanceUntilIdle()
        assertTrue(review.cards.value.isEmpty())
        assertEquals(ReviewHubMessage.RemovedFromReview("題1", 1), messages.last())
        vm.undoRemoveFromReview(1)
        advanceUntilIdle()
        assertEquals(1, review.cards.value.size)
    }

    @Test
    fun 操作に失敗したら_知らせる() = runTest {
        val target = bookmark(1)
        bookmarks.bookmarks.value = listOf(target)
        val vm = hub()
        val messages = mutableListOf<ReviewHubMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.toList(messages) }
        advanceUntilIdle()
        readingList.failure = IllegalStateException("boom")

        vm.addToReadingList(target)
        advanceUntilIdle()
        assertTrue(messages.last() is ReviewHubMessage.Failed)
    }

    // ---- 復習の画面 ----

    @Test
    fun 復習は日時の来たものを早い順に集め_ブックマークの無いカードは出さない() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1, summary = "要約1"), bookmark(2, summary = "要約2"))
        review.cards.value = listOf(dueCard(2).copy(dueAt = 20), dueCard(1).copy(dueAt = 10), dueCard(404).copy(dueAt = 5))
        val vm = session()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf(1L, 2L), state.queue.map { it.bookmark.id })
        assertEquals(2, state.total)
        assertFalse(state.showAnswer)
        assertEquals("題1", state.current!!.bookmark.title)
        // 各答えのあとの次の復習の言い方
        assertEquals(
            mapOf(ReviewGrade.AGAIN to "10分後", ReviewGrade.HARD to "明日", ReviewGrade.GOOD to "明日", ReviewGrade.EASY to "4日後"),
            state.intervalLabels
        )
    }

    @Test
    fun 答えを見るまでは答えられず_答えると次へ進む() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1), bookmark(2))
        review.cards.value = listOf(dueCard(1).copy(dueAt = 10), dueCard(2).copy(dueAt = 20))
        val vm = session()
        advanceUntilIdle()

        // 答えを見る前の答えは受け付けない
        vm.answer(ReviewGrade.GOOD)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.queue.size)
        assertTrue(review.calls.none { it.startsWith("grade") })

        vm.reveal()
        assertTrue(vm.uiState.value.showAnswer)
        vm.answer(ReviewGrade.GOOD)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(2L), state.queue.map { it.bookmark.id })
        assertFalse(state.showAnswer)
        assertEquals(1, state.answered)
        assertEquals(listOf("grade 1 GOOD"), review.calls)
        // 保存した次の日時
        assertEquals(1, review.cards.value.first { it.bookmarkId == 1L }.intervalDays)
    }

    @Test
    fun もう一度は_この回の最後に出し直す() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1), bookmark(2))
        review.cards.value = listOf(dueCard(1).copy(dueAt = 10), dueCard(2).copy(dueAt = 20))
        val vm = session()
        advanceUntilIdle()

        vm.reveal()
        vm.answer(ReviewGrade.AGAIN)
        advanceUntilIdle()

        assertEquals(listOf(2L, 1L), vm.uiState.value.queue.map { it.bookmark.id })
        // 出し直すカードは、もう一度を選んだ後の状態(間隔 0・忘れた回数 1)
        val again = vm.uiState.value.queue.last().card
        assertEquals(0, again.intervalDays)
        assertEquals(1, again.lapses)

        // 最後まで答えると終わり
        vm.reveal(); vm.answer(ReviewGrade.EASY)
        advanceUntilIdle()
        vm.reveal(); vm.answer(ReviewGrade.GOOD)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isFinished)
        assertEquals(3, vm.uiState.value.answered)
    }

    @Test
    fun 復習するものが無ければ_すぐ終わりの状態() = runTest {
        val vm = session()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isFinished)
        assertNull(vm.uiState.value.current)
        assertEquals(0, vm.uiState.value.answered)
    }

    @Test
    fun 一度に出す件数には上限がある() = runTest {
        bookmarks.bookmarks.value = (1L..30L).map { bookmark(it) }
        review.cards.value = (1L..30L).map { dueCard(it).copy(dueAt = it) }
        val vm = session()
        advanceUntilIdle()
        assertEquals(com.unchunks.echomark.domain.review.SpacedRepetition.SESSION_LIMIT, vm.uiState.value.queue.size)
    }

    @Test
    fun 答えの保存に失敗したら_エラーを出して同じカードのまま() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1))
        review.cards.value = listOf(dueCard(1))
        val vm = session()
        advanceUntilIdle()
        review.failure = IllegalStateException("boom")

        vm.reveal()
        vm.answer(ReviewGrade.GOOD)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.errorMessage)
        assertEquals(1, vm.uiState.value.queue.size)
        vm.dismissError()
        assertNull(vm.uiState.value.errorMessage)
    }

    // ---- 詳細画面のメニュー ----

    @Test
    fun 詳細画面のメニューで_あとで読むと復習を入れたり外したりできる() = runTest {
        val vm = ReadLaterViewModel(readingList, review)
        backgroundScope.launch { vm.reviewIds.collect {} }
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.toList(messages) }
        val target = bookmark(1)
        bookmarks.bookmarks.value = listOf(target)
        advanceUntilIdle()

        vm.toggleReadingList(target)
        advanceUntilIdle()
        assertEquals(listOf(1L), readingList.ids)
        assertTrue(messages.last().contains("入れました"))
        // 入れているブックマークでもう一度押すと外す
        vm.toggleReadingList(target.copy(readingListAt = now))
        advanceUntilIdle()
        assertTrue(readingList.ids.isEmpty())

        vm.toggleReview(target)
        advanceUntilIdle()
        assertEquals(setOf(1L), vm.reviewIds.value)
        assertTrue(messages.last().contains("明日"))
        vm.toggleReview(target)
        advanceUntilIdle()
        assertTrue(vm.reviewIds.value.isEmpty())
    }

    @Test
    fun 詳細画面のメニューの操作に失敗したら_知らせる() = runTest {
        val vm = ReadLaterViewModel(readingList, review)
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.toList(messages) }
        review.failure = IllegalStateException("boom")
        vm.toggleReview(bookmark(1))
        advanceUntilIdle()
        assertEquals(listOf("操作できませんでした。"), messages)
    }
}

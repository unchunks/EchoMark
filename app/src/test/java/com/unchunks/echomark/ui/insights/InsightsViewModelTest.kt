package com.unchunks.echomark.ui.insights

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.insights.TimelineBasis
import com.unchunks.echomark.domain.insights.TimelineRow
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeTagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Tokyo")
    private val bookmarks = FakeBookmarkRepository()
    private val tags = FakeTagRepository()

    private fun at(month: Int, day: Int): Long =
        LocalDateTime.of(2026, month, day, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun saved(id: Long, month: Int, day: Int, vararg tagNames: String) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id",
        createdAt = at(month, day), lastAccessedAt = at(month, day), tags = tagNames.toList()
    )

    private fun TestScope.viewModel(): InsightsViewModel {
        val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        return InsightsViewModel(bookmarks, tags, dispatchers).also {
            it.clock = { at(6, 15) }
            it.zone = zone
            backgroundScope.launch { it.uiState.collect {} }
        }
    }

    @Test
    fun 読み込み中から始まり集計が出る() = runTest {
        bookmarks.bookmarks.value = listOf(saved(1, 6, 1, "料理"), saved(2, 6, 2, "料理"))
        tags.tags.value = listOf(TagWithCount(1, "料理", 2))
        val vm = viewModel()
        assertTrue(vm.uiState.value.isLoading)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.bookmarkCount)
        assertEquals(listOf("料理"), state.tagCloud.entries.map { it.tag.name })
        assertEquals(2, state.stats.total)
        assertEquals(2, state.timeline.count { it is TimelineRow.Item })
        assertNotNull(state.interests)
    }

    @Test
    fun ブックマークが変わると集計が追従する() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.bookmarkCount)

        bookmarks.bookmarks.value = listOf(saved(1, 6, 1))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.bookmarkCount)
    }

    @Test
    fun 並べる基準を変えるとタイムラインが並べ直される() = runTest {
        bookmarks.bookmarks.value = listOf(
            saved(1, 1, 1).copy(lastAccessedAt = at(6, 1)),
            saved(2, 3, 1)
        )
        val vm = viewModel()
        advanceUntilIdle()
        fun ids() = vm.uiState.value.timeline.filterIsInstance<TimelineRow.Item>().map { it.bookmark.id }
        assertEquals(listOf(2L, 1L), ids())

        vm.setTimelineBasis(TimelineBasis.LAST_OPENED)
        advanceUntilIdle()
        assertEquals(TimelineBasis.LAST_OPENED, vm.uiState.value.timelineBasis)
        assertEquals(listOf(1L, 2L), ids())
    }

    @Test
    fun 期間を変えると関心の変化の月数が変わる() = runTest {
        bookmarks.bookmarks.value = listOf(saved(1, 6, 1, "a"))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(6, vm.uiState.value.interests?.months?.size)

        vm.setInterestMonths(12)
        advanceUntilIdle()
        assertEquals(12, vm.uiState.value.interestMonths)
        assertEquals(12, vm.uiState.value.interests?.months?.size)
    }

    @Test
    fun 数千件でも集計できる() = runTest {
        bookmarks.bookmarks.value = (1..4000L).map { saved(it, 1 + (it % 6).toInt(), 1 + (it % 27).toInt(), "t${it % 50}") }
        tags.tags.value = (0 until 50).map { TagWithCount(it.toLong(), "t$it", 80) }
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(4000, vm.uiState.value.bookmarkCount)
        assertEquals(4000, vm.uiState.value.timeline.count { it is TimelineRow.Item })
        assertEquals(50, vm.uiState.value.tagCloud.entries.size)
    }
}

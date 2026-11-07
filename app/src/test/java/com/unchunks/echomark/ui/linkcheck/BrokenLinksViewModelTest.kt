package com.unchunks.echomark.ui.linkcheck

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.linkcheck.LinkCheckState
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeLinkCheckRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrokenLinksViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeLinkCheckRepository()
    private val settings = FakeAppSettingsRepository()

    private fun bookmark(id: Long) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id", contentUri = "https://example.com/$id", createdAt = id, lastAccessedAt = id
    )

    private fun dead(at: Long) = LinkCheckState(checkedAt = at, failureCount = 3, firstFailedAt = at - 10, deadAt = at, lastReason = "ページが見つかりません(HTTP 404)")

    private fun TestScope.start(viewModel: BrokenLinksViewModel): MutableList<BrokenLinksMessage> {
        val messages = mutableListOf<BrokenLinksMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun リンク切れを新しく切れた順に出し_設定を反映する() = runTest {
        repository.bookmarks.value = listOf(bookmark(1), bookmark(2), bookmark(3))
        repository.states.value = mapOf(1L to dead(100), 2L to dead(200), 3L to LinkCheckState(checkedAt = 5))
        val viewModel = BrokenLinksViewModel(repository, settings)
        start(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.enabled)
        assertEquals(listOf(2L, 1L), state.links.map { it.bookmark.id })
    }

    @Test
    fun 夜の確認のオンオフを切り替えると設定に保存される() = runTest {
        val viewModel = BrokenLinksViewModel(repository, settings)
        start(viewModel)
        advanceUntilIdle()

        viewModel.setEnabled(false)
        advanceUntilIdle()

        assertFalse(settings.linkCheckEnabledFlow.value)
        assertFalse(viewModel.uiState.value.enabled)
    }

    @Test
    fun 確認しないにすると一覧から外れ_元に戻せるよう知らせる() = runTest {
        repository.bookmarks.value = listOf(bookmark(1))
        repository.states.value = mapOf(1L to dead(100))
        val viewModel = BrokenLinksViewModel(repository, settings)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.mute(viewModel.uiState.value.links.single())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.links.isEmpty())
        assertEquals(listOf<BrokenLinksMessage>(BrokenLinksMessage.Muted(1, "題1")), messages)

        viewModel.unmute(1)
        advanceUntilIdle()
        assertEquals(listOf(1L), viewModel.uiState.value.links.map { it.bookmark.id })
    }

    @Test
    fun もう一度確認して開けたら一覧から消える() = runTest {
        repository.bookmarks.value = listOf(bookmark(1))
        repository.states.value = mapOf(1L to dead(100))
        repository.checkResult = LinkCheckState(checkedAt = 300)
        val viewModel = BrokenLinksViewModel(repository, settings)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.recheck(viewModel.uiState.value.links.single())
        advanceUntilIdle()

        assertEquals(listOf(1L), repository.checkCalls)
        assertTrue(viewModel.uiState.value.links.isEmpty())
        assertTrue(viewModel.uiState.value.checkingIds.isEmpty())
        assertEquals(listOf<BrokenLinksMessage>(BrokenLinksMessage.Checked(LinkCheckState(checkedAt = 300))), messages)
    }

    @Test
    fun 確認が失敗しても確認中のままにならず_結果なしで知らせる() = runTest {
        repository.bookmarks.value = listOf(bookmark(1))
        repository.states.value = mapOf(1L to dead(100))
        repository.failure = IllegalStateException("想定外")
        val viewModel = BrokenLinksViewModel(repository, settings)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.recheck(viewModel.uiState.value.links.single())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.checkingIds.isEmpty())
        assertEquals(listOf<BrokenLinksMessage>(BrokenLinksMessage.Checked(null)), messages)
        assertEquals(1, viewModel.uiState.value.links.size)
    }
}

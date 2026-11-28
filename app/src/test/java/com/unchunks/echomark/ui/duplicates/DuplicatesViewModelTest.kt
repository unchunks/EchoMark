package com.unchunks.echomark.ui.duplicates

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicatePair
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateReason
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.repository.DuplicateMatch
import com.unchunks.echomark.domain.repository.DuplicateScan
import com.unchunks.echomark.domain.repository.MergeUndo
import com.unchunks.echomark.domain.repository.RelatedGroup
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeDuplicateRepository
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
class DuplicatesViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeDuplicateRepository()
    private val settings = FakeAppSettingsRepository()

    private fun bookmark(id: Long) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id", createdAt = id, lastAccessedAt = id
    )

    private fun match(a: Long, b: Long, keepId: Long = a) = DuplicateMatch(
        first = bookmark(a), second = bookmark(b), reason = DuplicateReason.SIMILAR_CONTENT, distance = 0.03, suggestedKeepId = keepId
    )

    private fun scan(vararg matches: DuplicateMatch, groups: List<RelatedGroup> = emptyList(), embedded: Int = 5, total: Int = 5) =
        DuplicateScan(matches.toList(), groups, embedded, total)

    private fun TestScope.start(viewModel: DuplicatesViewModel): MutableList<DuplicatesMessage> {
        val messages = mutableListOf<DuplicatesMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun 開くと今の厳しさで探して結果を出す() = runTest {
        repository.scanResult = scan(match(1, 2), match(3, 4))
        val viewModel = DuplicatesViewModel(repository, settings)
        start(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf(DuplicateSensitivity.STANDARD), repository.scanCalls)
        assertEquals(listOf(DuplicatePair.of(1, 2), DuplicatePair.of(3, 4)), state.duplicates.map { it.pair })
        assertFalse(state.indexIncomplete)
    }

    @Test
    fun 索引が全部でないときは知らせる() = runTest {
        repository.scanResult = scan(embedded = 2, total = 5)
        val viewModel = DuplicatesViewModel(repository, settings)
        start(viewModel)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.indexIncomplete)
        assertEquals(2, viewModel.uiState.value.embeddedCount)
    }

    @Test
    fun 厳しさを変えるとその設定で探し直す() = runTest {
        val viewModel = DuplicatesViewModel(repository, settings)
        start(viewModel)
        advanceUntilIdle()

        viewModel.setSensitivity(DuplicateSensitivity.LOOSE)
        advanceUntilIdle()

        assertEquals(listOf(DuplicateSensitivity.STANDARD, DuplicateSensitivity.LOOSE), repository.scanCalls)
        assertEquals(DuplicateSensitivity.LOOSE, viewModel.uiState.value.sensitivity)
    }

    @Test
    fun 探し直すを押すと同じ厳しさでもう一度探す() = runTest {
        val viewModel = DuplicatesViewModel(repository, settings)
        start(viewModel)
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, repository.scanCalls.size)
    }

    @Test
    fun 探すのに失敗したらエラーを出す() = runTest {
        repository.failure = IllegalStateException("壊れた")
        val viewModel = DuplicatesViewModel(repository, settings)
        start(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.errorMessage != null)
    }

    @Test
    fun 統合すると消した方を含む候補とまとまりを結果から外し_元に戻す記録を知らせる() = runTest {
        val members = listOf(bookmark(1), bookmark(2), bookmark(3), bookmark(4))
        repository.scanResult = scan(match(1, 2), match(2, 3), match(8, 9), groups = listOf(RelatedGroup("グループ", members)))
        val undo = MergeUndo(bookmark(1), bookmark(2), emptyList())
        repository.mergeResult = undo
        val viewModel = DuplicatesViewModel(repository, settings)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.merge(viewModel.uiState.value.duplicates.first(), keepId = 1)
        advanceUntilIdle()

        assertEquals(listOf(1L to 2L), repository.merges)
        // 2 を含む組(1,2)と(2,3)が消え、まとまりからも 2 が外れる
        assertEquals(listOf(DuplicatePair.of(8, 9)), viewModel.uiState.value.duplicates.map { it.pair })
        assertEquals(listOf(1L, 3L, 4L), viewModel.uiState.value.groups.single().bookmarks.map { it.id })
        assertEquals(listOf(DuplicatesMessage.Merged("題1", undo)), messages)
    }

    @Test
    fun まとまりは3件に満たなくなったら消える() = runTest {
        val members = listOf(bookmark(1), bookmark(2), bookmark(3))
        repository.scanResult = scan(match(1, 2), groups = listOf(RelatedGroup("グループ", members)))
        repository.mergeResult = MergeUndo(bookmark(1), bookmark(2), emptyList())
        val viewModel = DuplicatesViewModel(repository, settings)
        start(viewModel)
        advanceUntilIdle()

        viewModel.merge(viewModel.uiState.value.duplicates.single(), keepId = 1)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.groups.isEmpty())
    }

    @Test
    fun 残す方は2番目でも選べる() = runTest {
        repository.scanResult = scan(match(1, 2))
        repository.mergeResult = MergeUndo(bookmark(2), bookmark(1), emptyList())
        val viewModel = DuplicatesViewModel(repository, settings)
        start(viewModel)
        advanceUntilIdle()

        viewModel.merge(viewModel.uiState.value.duplicates.single(), keepId = 2)
        advanceUntilIdle()

        assertEquals(listOf(2L to 1L), repository.merges)
    }

    @Test
    fun 統合しようとした相手がもう無ければ失敗を知らせる() = runTest {
        repository.scanResult = scan(match(1, 2))
        repository.mergeResult = null
        val viewModel = DuplicatesViewModel(repository, settings)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.merge(viewModel.uiState.value.duplicates.single(), keepId = 1)
        advanceUntilIdle()

        assertTrue(messages.single() is DuplicatesMessage.Failed)
    }

    @Test
    fun 両方残すとその組が候補から外れる() = runTest {
        repository.scanResult = scan(match(1, 2), match(3, 4))
        val viewModel = DuplicatesViewModel(repository, settings)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.keepBoth(viewModel.uiState.value.duplicates.first())
        advanceUntilIdle()

        assertEquals(listOf(DuplicatePair.of(1, 2)), repository.keptBoth)
        assertEquals(listOf(DuplicatePair.of(3, 4)), viewModel.uiState.value.duplicates.map { it.pair })
        assertEquals(listOf<DuplicatesMessage>(DuplicatesMessage.KeptBoth), messages)
    }

    @Test
    fun 元に戻すと戻して探し直す() = runTest {
        val undo = MergeUndo(bookmark(1), bookmark(2), emptyList())
        val viewModel = DuplicatesViewModel(repository, settings)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.undoMerge(undo)
        advanceUntilIdle()

        assertEquals(listOf(undo), repository.undone)
        assertEquals(2, repository.scanCalls.size)
        assertEquals(listOf<DuplicatesMessage>(DuplicatesMessage.UndoDone), messages)
    }
}

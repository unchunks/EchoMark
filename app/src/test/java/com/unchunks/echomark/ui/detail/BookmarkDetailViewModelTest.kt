package com.unchunks.echomark.ui.detail

import androidx.lifecycle.SavedStateHandle
import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.SummarySource
import com.unchunks.echomark.domain.model.SummaryVersion
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeProcessingStatsRepository
import com.unchunks.echomark.testing.testBookmark
import com.unchunks.echomark.ui.common.RecentlyDeletedBookmarks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkDetailViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeBookmarkRepository()
    private val recentlyDeleted = RecentlyDeletedBookmarks()
    private val stats = FakeProcessingStatsRepository()

    private fun createViewModel(id: Long = 1L) =
        BookmarkDetailViewModel(SavedStateHandle(mapOf("bookmarkId" to id)), repository, recentlyDeleted, stats)

    @Test
    fun 処理の進み具合と段ごとの平均の所要時間を出す() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1))
        val key = StageDurationKey(ProcessingStage.ANALYZE, ContentKind.MEMO)
        stats.averages.value = mapOf(key to 30_000L)
        repository.stageProgress.value = 0.25f
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        assertEquals(0.25f, viewModel.uiState.value.stageProgress)
        assertEquals(mapOf(key to 30_000L), viewModel.uiState.value.stageAverages)
    }

    private fun TestScope.start(viewModel: BookmarkDetailViewModel): MutableList<BookmarkDetailMessage> {
        val messages = mutableListOf<BookmarkDetailMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun 開くと最終アクセスを記録しブックマークと関連とタグ候補が出る() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1), testBookmark(2))
        repository.related = listOf(testBookmark(2))
        repository.tags.value = listOf(Tag(1, "kotlin"))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(1L), repository.accessedIds)
        assertFalse(state.isLoading)
        assertEquals(1L, state.bookmark?.id)
        assertEquals(listOf(2L), state.related.map { it.id })
        assertEquals(listOf("kotlin"), state.allTagNames)
    }

    @Test
    fun 存在しないIDなら見つからない状態になる() = runTest {
        val viewModel = createViewModel(id = 99)
        start(viewModel)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.bookmark)
    }

    @Test
    fun タグの追加は前後の空白と先頭のシャープを除き外すこともできる() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1))
        val viewModel = createViewModel()
        start(viewModel)

        viewModel.addTag("  #Kotlin ")
        viewModel.addTag("   ")
        advanceUntilIdle()
        assertEquals(listOf("Kotlin"), viewModel.uiState.value.bookmark?.tags)

        viewModel.removeTag("Kotlin")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), viewModel.uiState.value.bookmark?.tags)
    }

    @Test
    fun タグを外すと取り消しのメッセージを出し_元に戻すと付け直す() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(tags = listOf("Kotlin", "Android")))
        val viewModel = createViewModel()
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.removeTag("Kotlin")
        advanceUntilIdle()
        assertEquals(listOf("Android"), viewModel.uiState.value.bookmark?.tags)
        assertEquals(listOf<BookmarkDetailMessage>(BookmarkDetailMessage.TagRemoved("Kotlin")), messages)

        viewModel.addTag("Kotlin")
        advanceUntilIdle()
        assertEquals(setOf("Kotlin", "Android"), viewModel.uiState.value.bookmark?.tags?.toSet())
    }

    @Test
    fun お気に入りとアーカイブを切り替えアーカイブは取り消しのメッセージを出す() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1))
        val viewModel = createViewModel()
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.toggleFavorite()
        viewModel.setArchived(true)
        advanceUntilIdle()

        val bookmark = viewModel.uiState.value.bookmark!!
        assertTrue(bookmark.isFavorite)
        assertTrue(bookmark.isArchived)
        assertEquals(listOf<BookmarkDetailMessage>(BookmarkDetailMessage.ArchiveChanged(true)), messages)

        // 取り消しはメッセージを出さない
        viewModel.setArchived(false, notify = false)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.bookmark!!.isArchived)
        assertEquals(1, messages.size)
    }

    @Test
    fun 編集を保存すると再処理を提案するメッセージが出て空のタイトルは保存しない() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1))
        val viewModel = createViewModel()
        val messages = start(viewModel)

        viewModel.saveEdit("  ", "x")
        viewModel.saveEdit(" 新しいタイトル ", "  ")
        advanceUntilIdle()

        val bookmark = viewModel.uiState.value.bookmark!!
        assertEquals("新しいタイトル", bookmark.title)
        assertNull(bookmark.content)
        assertEquals(listOf<BookmarkDetailMessage>(BookmarkDetailMessage.Edited), messages)
    }

    @Test
    fun 再処理するとAI状態が処理待ちに戻る() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(aiStatus = AiStatus.FAILED))
        val viewModel = createViewModel()
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.reprocess()
        advanceUntilIdle()

        assertEquals(listOf(1L), repository.reprocessedIds)
        assertEquals(AiStatus.PENDING, viewModel.uiState.value.bookmark?.aiStatus)
        assertEquals(listOf<BookmarkDetailMessage>(BookmarkDetailMessage.ReprocessStarted), messages)
    }

    @Test
    fun 要約だけ_タグだけを作り直せる() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        viewModel.reprocess(AnalysisScope.SUMMARY)
        viewModel.reprocess(AnalysisScope.TAGS)
        advanceUntilIdle()

        assertEquals(listOf(1L to AnalysisScope.SUMMARY, 1L to AnalysisScope.TAGS), repository.reprocessCalls)
    }

    @Test
    fun 削除すると一覧で取り消せるよう知らせてから画面を閉じる() = runTest {
        val target = testBookmark(1)
        repository.bookmarks.value = listOf(target)
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()
        var closed = false

        viewModel.delete { closed = true }
        advanceUntilIdle()

        assertTrue(closed)
        assertEquals(listOf(target), repository.deleted)
        assertEquals(target, recentlyDeleted.consume())
    }

    @Test
    fun タグ候補は部分一致で付いていないものを前方一致優先で出す() {
        val all = listOf("Kotlin", "Kotlin Coroutines", "Android", "コトリン", "mykotlin")
        assertEquals(listOf("Kotlin Coroutines", "mykotlin"), tagSuggestions("kot", all, current = listOf("Kotlin")))
        assertEquals(emptyList<String>(), tagSuggestions("  ", all, current = emptyList()))
        assertEquals(listOf("Android"), tagSuggestions("#andr", all, current = emptyList()))
    }
    @Test
    fun 要約を手で直すと保存して知らせる_空白だけなら何もしない() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1))
        val viewModel = createViewModel()
        val messages = start(viewModel)

        viewModel.editSummary("   ")
        viewModel.editSummary("直した要約")
        advanceUntilIdle()

        assertEquals(listOf(1L to "直した要約"), repository.editedSummaries)
        assertEquals(listOf<BookmarkDetailMessage>(BookmarkDetailMessage.SummaryEdited), messages)
    }

    @Test
    fun 履歴を出し_版に戻せる() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1))
        val versions = listOf(
            SummaryVersion(2, 1, "新しい要約", SummarySource.MANUAL, null, null, 200),
            SummaryVersion(1, 1, "古い要約", SummarySource.AI, "端末内 AI", "日本語・文章", 100)
        )
        repository.summaryHistory.value = versions
        val viewModel = createViewModel()
        val messages = start(viewModel)
        advanceUntilIdle()

        assertEquals(versions, viewModel.uiState.value.summaryHistory)
        viewModel.restoreSummary(1)
        advanceUntilIdle()

        assertEquals(listOf(1L), repository.restoredSummaryVersions)
        assertEquals(listOf<BookmarkDetailMessage>(BookmarkDetailMessage.SummaryRestored), messages)
    }

    @Test
    fun 直した後のAIで要約し直すは_手で直した要約なら要約を作り直す() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(summary = "直した要約", summaryEdited = true))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        viewModel.reprocessAfterEdit()
        advanceUntilIdle()

        assertEquals(listOf(1L to AnalysisScope.SUMMARY), repository.reprocessCalls)
    }

    @Test
    fun 直した後のAIで要約し直すは_手で直していなければ全部やり直す() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(summary = "AI の要約"))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        viewModel.reprocessAfterEdit()
        advanceUntilIdle()

        assertEquals(listOf(1L to AnalysisScope.ALL), repository.reprocessCalls)
    }
}

package com.unchunks.echomark.ui.podcast

import androidx.lifecycle.SavedStateHandle
import com.unchunks.components.speechtext.ReadingEntry
import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastGenerationException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakePodcastRepository
import com.unchunks.echomark.ui.navigation.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** ポッドキャストを作る画面と、エピソードの一覧の ViewModel。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastViewModelsTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val bookmarks = FakeBookmarkRepository().apply {
        bookmarks.value = (1L..12L).map { Bookmark(id = it, type = BookmarkType.TEXT, title = "メモ$it", createdAt = 0, lastAccessedAt = 0) }
    }
    private val podcasts = FakePodcastRepository()
    private val settings = FakeAppSettingsRepository(LlmBackend.LOCAL)
    private val playback = com.unchunks.echomark.testing.FakePodcastPlayback()

    private fun createViewModel(ids: String) =
        PodcastCreateViewModel(SavedStateHandle(mapOf(Routes.ARG_PODCAST_BOOKMARK_IDS to ids)), bookmarks, podcasts)

    private fun <T> TestScope.collect(flow: kotlinx.coroutines.flow.Flow<T>): MutableList<T> {
        val items = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(items) }
        return items
    }

    @Test
    fun IDを読む() {
        assertEquals(listOf(3L, 1L), PodcastCreateViewModel.parseIds("3, 1,x,,3,-2,0"))
        assertEquals(emptyList<Long>(), PodcastCreateViewModel.parseIds(null))
    }

    @Test
    fun 選んだ順にタイトルを出し_上限を超えた分は入れない() = runTest {
        val viewModel = createViewModel((12 downTo 1).joinToString(","))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals((12 downTo 3).map { "メモ$it" }, state.titles)
        assertEquals(2, state.skipped)
    }

    @Test
    fun 話し方を選んで作り始める() = runTest {
        val viewModel = createViewModel("2,1")
        val events = collect(viewModel.events)
        advanceUntilIdle()

        viewModel.setFormat(PodcastFormat.DUO)
        viewModel.create()
        advanceUntilIdle()

        assertEquals(listOf(listOf(2L, 1L) to PodcastFormat.DUO), podcasts.created)
        assertEquals(listOf(PodcastCreateEvent.Started("ep1")), events)
    }

    @Test
    fun 作り始められなければ理由を知らせる() = runTest {
        podcasts.createFailure = PodcastGenerationException("選んだブックマークが見つかりません。")
        val viewModel = createViewModel("1")
        val events = collect(viewModel.events)
        advanceUntilIdle()

        viewModel.create()
        advanceUntilIdle()

        assertEquals(listOf(PodcastCreateEvent.Failed("選んだブックマークが見つかりません。")), events)
        assertFalse(viewModel.uiState.value.isCreating)
    }

    @Test
    fun 見つからなければ作れない() = runTest {
        val viewModel = createViewModel("99")
        advanceUntilIdle()
        assertEquals("選んだブックマークが見つかりません。", viewModel.uiState.value.errorMessage)
    }

    private fun episode(id: String, createdAt: Long, status: EpisodeStatus = EpisodeStatus.READY) =
        PodcastEpisode(id, "題$id", createdAt, PodcastFormat.SOLO, emptyList(), status)

    @Test
    fun 一覧と_古いものを消す() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        podcasts.episodesFlow.value = listOf(episode("new", 40 * day), episode("old", day))
        val viewModel = PodcastEpisodesViewModel(podcasts, settings, playback).apply { clock = { 40 * day } }
        val messages = collect(viewModel.messages)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("new", "old"), viewModel.uiState.value.episodes.map { it.id })

        viewModel.deleteOlderThan(30)
        advanceUntilIdle()

        assertEquals(listOf(10 * day), podcasts.olderThanCalls)
        // 消すものを再生していれば止める
        assertEquals(listOf("stop:old"), playback.calls)
        assertEquals(listOf("new"), viewModel.uiState.value.episodes.map { it.id })
        assertEquals(listOf<PodcastEpisodesMessage>(PodcastEpisodesMessage.Deleted(1)), messages)
    }

    @Test
    fun 読みを直して辞書に入れ_作り直す() = runTest {
        val target = episode("a", 0)
        podcasts.episodesFlow.value = listOf(target.copy(sources = listOf(com.unchunks.echomark.domain.podcast.EpisodeSource(5, "x", null, null))))
        val viewModel = PodcastEpisodesViewModel(podcasts, settings, playback)
        val messages = collect(viewModel.messages)

        viewModel.saveReading("日本橋", "にほんばし")
        viewModel.saveReading("日本橋", "")
        viewModel.recreate(podcasts.episodesFlow.value.single())
        advanceUntilIdle()

        assertEquals(listOf(ReadingEntry("日本橋", "にほんばし")), settings.readingDictionaryFlow.value)
        assertEquals(listOf(listOf(5L) to PodcastFormat.SOLO), podcasts.created)
        assertEquals(
            listOf(
                PodcastEpisodesMessage.ReadingSaved("日本橋"),
                PodcastEpisodesMessage.Failed("読みを入れてください"),
                PodcastEpisodesMessage.Recreating("題a")
            ),
            messages.sortedBy { it.javaClass.simpleName.let { name -> listOf("ReadingSaved", "Failed", "Recreating").indexOf(name) } }
        )
    }
}

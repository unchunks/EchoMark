package com.unchunks.echomark.ui.podcast

import androidx.lifecycle.SavedStateHandle
import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.podcast.EpisodeLine
import com.unchunks.echomark.domain.podcast.EpisodeSource
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.EpisodeTimeline
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastPlaybackState
import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.testing.FakePodcastPlayback
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 再生の画面の ViewModel と、台本と音声の位置の対応。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastPlayerViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val lines = listOf(
        EpisodeLine(PodcastSpeaker.HOST, "始まり", "始まり", null, 0),
        EpisodeLine(PodcastSpeaker.HOST, "1件目は", "1件目は", 1, 2_000),
        EpisodeLine(PodcastSpeaker.HOST, "URL だけ", "", 1, null),
        EpisodeLine(PodcastSpeaker.GUEST, "へえ", "へえ", 1, 5_000),
        EpisodeLine(PodcastSpeaker.HOST, "2件目は", "2件目は", 2, 9_000),
        EpisodeLine(PodcastSpeaker.HOST, "終わり", "終わり", null, 12_000)
    )
    private val episode = PodcastEpisode(
        id = "ep", title = "題", createdAt = 0, format = PodcastFormat.DUO,
        sources = listOf(EpisodeSource(1, "a", null, null), EpisodeSource(2, "b", null, null)),
        status = EpisodeStatus.READY, lines = lines, audioFile = "episode.wav", durationMs = 14_000
    )
    private val repository = FakePodcastRepository().apply { episodesFlow.value = listOf(episode) }
    private val playback = FakePodcastPlayback()

    private fun TestScope.viewModel(id: String = "ep"): Pair<PodcastPlayerViewModel, MutableList<String>> {
        val viewModel = PodcastPlayerViewModel(SavedStateHandle(mapOf(Routes.ARG_PODCAST_EPISODE_ID to id)), repository, playback)
        val messages = mutableListOf<String>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return viewModel to messages
    }

    @Test
    fun 開くと最初から再生し_読んでいる行が分かる() = runTest {
        val (viewModel, _) = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("play:ep:null"), playback.calls)
        playback.stateFlow.value = playback.stateFlow.value.copy(positionMs = 6_000)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state.isCurrent)
        assertTrue(state.isPlaying)
        assertEquals(3, state.currentLine)
        assertEquals(14_000, state.durationMs)
    }

    @Test
    fun 再生中のエピソードを開き直しても_始めからにしない() = runTest {
        playback.stateFlow.value = PodcastPlaybackState(episodeId = "ep", isPlaying = true, positionMs = 9_500, durationMs = 14_000)
        val (viewModel, _) = viewModel()
        advanceUntilIdle()

        assertTrue(playback.calls.isEmpty())
        assertEquals(4, viewModel.uiState.value.currentLine)
    }

    @Test
    fun 行と紹介から聞く() = runTest {
        val (viewModel, _) = viewModel()
        advanceUntilIdle()

        viewModel.playLine(3)
        viewModel.playLine(2) // 位置の無い行(読まなかった行)は何もしない
        viewModel.playSource(2)
        advanceUntilIdle()

        assertEquals(listOf("play:ep:null", "seekTo:5000", "seekTo:9000"), playback.calls)
    }

    @Test
    fun ほかのエピソードを再生していたら_選んだ位置から読み込み直す() = runTest {
        playback.stateFlow.value = PodcastPlaybackState(episodeId = "other", isPlaying = true, positionMs = 1_000)
        val (viewModel, _) = viewModel()
        advanceUntilIdle()
        playback.calls.clear()
        playback.stateFlow.value = PodcastPlaybackState(episodeId = "other", isPlaying = true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isCurrent)

        viewModel.playSource(1)
        advanceUntilIdle()

        assertEquals(listOf("play:ep:2000"), playback.calls)
    }

    @Test
    fun 一時停止と再開と速さと戻る() = runTest {
        val (viewModel, _) = viewModel()
        advanceUntilIdle()
        playback.calls.clear()

        viewModel.togglePlay()
        advanceUntilIdle()
        viewModel.togglePlay()
        viewModel.setSpeed(1.5f)
        viewModel.seekBy(-10_000)
        advanceUntilIdle()

        assertEquals(listOf("pause", "resume", "speed:1.5", "seekBy:-10000"), playback.calls)
        assertEquals(1.5f, viewModel.uiState.value.speed)
    }

    @Test
    fun 再生できなければ知らせる() = runTest {
        playback.playResult = false
        val (_, messages) = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("再生できませんでした。音声のファイルが無いかもしれません。"), messages)
    }

    @Test
    fun 消えたエピソードとできていないエピソード() = runTest {
        val (missing, _) = viewModel("gone")
        advanceUntilIdle()
        assertEquals("エピソードが見つかりません(削除されたかもしれません)。", missing.uiState.value.errorMessage)

        repository.episodesFlow.value = listOf(episode.copy(status = EpisodeStatus.GENERATING))
        val (generating, _) = viewModel()
        advanceUntilIdle()
        assertEquals("まだ聞けません。", generating.uiState.value.errorMessage)
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun 台本と位置の対応() {
        assertNull(EpisodeTimeline.currentLineIndex(lines.drop(1), 1_000))
        assertEquals(0, EpisodeTimeline.currentLineIndex(lines, 0))
        assertEquals(1, EpisodeTimeline.currentLineIndex(lines, 4_999))
        assertEquals(5, EpisodeTimeline.currentLineIndex(lines, 60_000))
        assertEquals(2_000L, EpisodeTimeline.sourceStartMs(lines, 1))
        assertNull(EpisodeTimeline.sourceStartMs(lines, 3))
        assertEquals("0:05", EpisodeTimeline.clock(5_400))
        assertEquals("12:03", EpisodeTimeline.clock(723_000))
        assertEquals("1:02:03", EpisodeTimeline.clock(3_723_000))
        assertEquals("1.25倍", speedLabel(1.25f))
        assertEquals("2倍", speedLabel(2f))
        assertEquals("0.75倍", speedLabel(0.75f))
    }
}

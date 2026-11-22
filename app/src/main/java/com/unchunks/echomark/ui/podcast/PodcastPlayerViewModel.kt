package com.unchunks.echomark.ui.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.EpisodeTimeline
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastPlayback
import com.unchunks.echomark.domain.podcast.PodcastRepository
import com.unchunks.echomark.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 再生の画面の状態。
 * @property isCurrent このエピソードを読み込んでいるか(違えば、位置・再生中は 0 と false)
 * @property currentLine 読んでいる行の番号
 */
data class PodcastPlayerUiState(
    val isLoading: Boolean = true,
    val episode: PodcastEpisode? = null,
    val isCurrent: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val currentLine: Int? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class PodcastPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    private val playback: PodcastPlayback
) : ViewModel() {

    private val episodeId: String = savedStateHandle.get<String>(Routes.ARG_PODCAST_EPISODE_ID).orEmpty()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<PodcastPlayerUiState> = combine(repository.observeEpisode(episodeId), playback.state) { episode, state ->
        when {
            episode == null -> PodcastPlayerUiState(isLoading = false, errorMessage = "エピソードが見つかりません(削除されたかもしれません)。")
            episode.status != EpisodeStatus.READY -> PodcastPlayerUiState(isLoading = false, episode = episode, errorMessage = "まだ聞けません。")
            else -> {
                val current = state.episodeId == episode.id
                val position = if (current) state.positionMs else 0
                PodcastPlayerUiState(
                    isLoading = false,
                    episode = episode,
                    isCurrent = current,
                    isPlaying = current && state.isPlaying,
                    positionMs = position,
                    durationMs = (if (current) state.durationMs else 0).takeIf { it > 0 } ?: episode.durationMs ?: 0,
                    speed = state.speed,
                    currentLine = if (current) EpisodeTimeline.currentLineIndex(episode.lines, position) else null
                )
            }
        }
    }
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "ポッドキャストを読み込めなかった")
            emit(PodcastPlayerUiState(isLoading = false, errorMessage = "ポッドキャストを読み込めませんでした。"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastPlayerUiState())

    init {
        // 開いたら再生する。通知から開いたとき(もう読み込んでいるとき)は、そのまま続ける
        viewModelScope.launch {
            val episode = repository.observeEpisode(episodeId).filterNotNull().first()
            if (episode.status == EpisodeStatus.READY && playback.state.value.episodeId != episode.id) start(episode, null)
        }
    }

    fun togglePlay() {
        val state = uiState.value
        val episode = state.episode ?: return
        when {
            !state.isCurrent -> viewModelScope.launch { start(episode, null) }
            state.isPlaying -> playback.pause()
            else -> playback.resume()
        }
    }

    fun seekTo(positionMs: Long) = jump(positionMs)

    fun seekBy(deltaMs: Long) {
        if (uiState.value.isCurrent) playback.seekBy(deltaMs) else jump((uiState.value.positionMs + deltaMs).coerceAtLeast(0))
    }

    fun setSpeed(speed: Float) = playback.setSpeed(speed)

    /** 台本の [index] 行目から聞く */
    fun playLine(index: Int) {
        uiState.value.episode?.lines?.getOrNull(index)?.startMs?.let(::jump)
    }

    /** [sourceIndex] 件目(1 始まり)の紹介から聞く */
    fun playSource(sourceIndex: Int) {
        val lines = uiState.value.episode?.lines ?: return
        EpisodeTimeline.sourceStartMs(lines, sourceIndex)?.let(::jump)
    }

    private fun jump(positionMs: Long) {
        val state = uiState.value
        val episode = state.episode ?: return
        if (state.isCurrent) {
            playback.seekTo(positionMs)
            if (!state.isPlaying) playback.resume()
        } else {
            viewModelScope.launch { start(episode, positionMs) }
        }
    }

    private suspend fun start(episode: PodcastEpisode, startMs: Long?) {
        if (!playback.play(episode, startMs)) messageChannel.send("再生できませんでした。音声のファイルが無いかもしれません。")
    }
}

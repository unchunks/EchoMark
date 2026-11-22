package com.unchunks.echomark.ui.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastProgress
import com.unchunks.echomark.domain.podcast.PodcastRepository
import com.unchunks.echomark.domain.podcast.ReadingDictionary
import com.unchunks.echomark.domain.podcast.ReadingEntry
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class PodcastEpisodesUiState(
    val isLoading: Boolean = true,
    val episodes: List<PodcastEpisode> = emptyList(),
    val progress: Map<String, PodcastProgress> = emptyMap(),
    val dictionary: List<ReadingEntry> = emptyList(),
    val errorMessage: String? = null
)

/** エピソードの一覧で一度だけ伝える出来事。 */
sealed interface PodcastEpisodesMessage {
    data class Deleted(val count: Int) : PodcastEpisodesMessage
    data class ReadingSaved(val surface: String) : PodcastEpisodesMessage
    data class Recreating(val title: String) : PodcastEpisodesMessage
    data class Failed(val message: String) : PodcastEpisodesMessage
}

@HiltViewModel
class PodcastEpisodesViewModel @Inject constructor(
    private val podcastRepository: PodcastRepository,
    private val appSettings: AppSettingsRepository
) : ViewModel() {

    /** テストで時刻を決めるため */
    internal var clock: () -> Long = System::currentTimeMillis

    private val messageChannel = Channel<PodcastEpisodesMessage>(Channel.BUFFERED)
    val messages: Flow<PodcastEpisodesMessage> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<PodcastEpisodesUiState> =
        combine(podcastRepository.episodes, podcastRepository.progress, appSettings.readingDictionary) { episodes, progress, dictionary ->
            PodcastEpisodesUiState(isLoading = false, episodes = episodes, progress = progress, dictionary = dictionary)
        }
            .catch { e ->
                if (e is CancellationException) throw e
                Timber.w(e, "ポッドキャストの一覧を読めなかった")
                emit(PodcastEpisodesUiState(isLoading = false, errorMessage = "ポッドキャストを読み込めませんでした。"))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastEpisodesUiState())

    fun audioFileOf(episode: PodcastEpisode): File? = podcastRepository.audioFileOf(episode)

    fun delete(episode: PodcastEpisode) = perform("削除できませんでした") {
        podcastRepository.delete(episode.id)
        PodcastEpisodesMessage.Deleted(1)
    }

    /** [days] 日より前に作ったものを消す */
    fun deleteOlderThan(days: Int) = perform("削除できませんでした") {
        PodcastEpisodesMessage.Deleted(podcastRepository.deleteOlderThan(clock() - TimeUnit.DAYS.toMillis(days.toLong())))
    }

    fun deleteAll() = perform("削除できませんでした") {
        val count = uiState.value.episodes.size
        podcastRepository.deleteAll()
        PodcastEpisodesMessage.Deleted(count)
    }

    /** 同じブックマーク・話し方で作り直す(読みの辞書を直したあとに) */
    fun recreate(episode: PodcastEpisode) = perform("作り直せませんでした") {
        podcastRepository.create(episode.sources.map { it.bookmarkId }, episode.format)
        PodcastEpisodesMessage.Recreating(episode.title)
    }

    /** エピソードで使った読みを直して、読みの辞書に入れる */
    fun saveReading(surface: String, reading: String) {
        val problem = ReadingEntry.problemOf(surface, reading)
        if (problem != null) {
            viewModelScope.launch { messageChannel.send(PodcastEpisodesMessage.Failed(problem)) }
            return
        }
        perform("読みを保存できませんでした") {
            appSettings.updateReadingDictionary { ReadingDictionary.upsert(it, ReadingEntry(surface, reading)) }
            PodcastEpisodesMessage.ReadingSaved(ReadingEntry.normalize(surface))
        }
    }

    private fun perform(failure: String, block: suspend () -> PodcastEpisodesMessage) {
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, failure)
                PodcastEpisodesMessage.Failed(e.message?.takeIf { e is com.unchunks.echomark.domain.podcast.PodcastGenerationException } ?: failure)
            }
            messageChannel.send(message)
        }
    }
}

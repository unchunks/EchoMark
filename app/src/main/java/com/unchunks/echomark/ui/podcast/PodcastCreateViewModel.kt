package com.unchunks.echomark.ui.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastGenerationException
import com.unchunks.echomark.domain.podcast.PodcastRepository
import com.unchunks.echomark.domain.podcast.PodcastTemplates
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ポッドキャストを作る画面の状態。
 * @property titles 紹介するブックマークのタイトル(紹介する順)
 * @property skipped 上限([PodcastTemplates.MAX_SOURCES])を超えて選んだため、入れない件数
 */
data class PodcastCreateUiState(
    val isLoading: Boolean = true,
    val titles: List<String> = emptyList(),
    val skipped: Int = 0,
    val format: PodcastFormat = PodcastFormat.SOLO,
    val isCreating: Boolean = false,
    val errorMessage: String? = null
)

/** 作り始めた(画面を閉じて一覧へ)・作れなかった。 */
sealed interface PodcastCreateEvent {
    data class Started(val episodeId: String) : PodcastCreateEvent
    data class Failed(val message: String) : PodcastCreateEvent
}

@HiltViewModel
class PodcastCreateViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookmarkRepository: BookmarkRepository,
    private val podcastRepository: PodcastRepository
) : ViewModel() {

    private val requestedIds: List<Long> = parseIds(savedStateHandle.get<String>(Routes.ARG_PODCAST_BOOKMARK_IDS))
    private val ids = requestedIds.take(PodcastTemplates.MAX_SOURCES)

    private val state = MutableStateFlow(PodcastCreateUiState(skipped = (requestedIds.size - ids.size).coerceAtLeast(0)))
    val uiState: StateFlow<PodcastCreateUiState> = state.asStateFlow()

    private val eventChannel = Channel<PodcastCreateEvent>(Channel.BUFFERED)
    val events: Flow<PodcastCreateEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val byId = bookmarkRepository.getBookmarksByIds(ids).associateBy { it.id }
                val titles = ids.mapNotNull { byId[it]?.let { b -> PodcastTemplates.spokenTitle(b.title, b.siteName) } }
                state.update {
                    it.copy(
                        isLoading = false,
                        titles = titles,
                        errorMessage = if (titles.isEmpty()) "選んだブックマークが見つかりません。" else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ポッドキャストにするブックマークを読めなかった")
                state.update { it.copy(isLoading = false, errorMessage = "ブックマークを読み込めませんでした。") }
            }
        }
    }

    fun setFormat(format: PodcastFormat) {
        state.update { it.copy(format = format) }
    }

    fun create() {
        val current = state.value
        if (current.isCreating || current.titles.isEmpty()) return
        state.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            val event = try {
                PodcastCreateEvent.Started(podcastRepository.create(ids, current.format))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PodcastGenerationException) {
                PodcastCreateEvent.Failed(e.message ?: "作り始められませんでした。")
            } catch (e: Exception) {
                Timber.w(e, "ポッドキャストを作り始められなかった")
                PodcastCreateEvent.Failed("作り始められませんでした。")
            }
            state.update { it.copy(isCreating = false) }
            eventChannel.send(event)
        }
    }

    companion object {
        /** カンマ区切りの ID。読めないもの・重なりは除く */
        fun parseIds(value: String?): List<Long> =
            value.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull()?.takeIf { id -> id > 0 } }.distinct()
    }
}

package com.unchunks.echomark.ui.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateDetector
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.DuplicateMatch
import com.unchunks.echomark.domain.repository.DuplicateRepository
import com.unchunks.echomark.domain.repository.MergeUndo
import com.unchunks.echomark.domain.repository.RelatedGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class DuplicatesUiState(
    /** 探している間は true(索引の大きさによっては数秒かかる) */
    val isLoading: Boolean = true,
    val sensitivity: DuplicateSensitivity = DuplicateSensitivity.DEFAULT,
    val duplicates: List<DuplicateMatch> = emptyList(),
    val groups: List<RelatedGroup> = emptyList(),
    /** 検索用の索引が全部できていない(できていない分は、URL が同じものしか見つからない) */
    val indexIncomplete: Boolean = false,
    val embeddedCount: Int = 0,
    val totalCount: Int = 0,
    val errorMessage: String? = null
)

/** 重複・関連の画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface DuplicatesMessage {
    /** 統合した。[undo] で元に戻せる */
    data class Merged(val keptTitle: String, val undo: MergeUndo) : DuplicatesMessage
    data object KeptBoth : DuplicatesMessage
    data object UndoDone : DuplicatesMessage
    data class Failed(val message: String) : DuplicatesMessage
}

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val duplicateRepository: DuplicateRepository,
    private val appSettings: AppSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicatesUiState())
    val uiState: StateFlow<DuplicatesUiState> = _uiState.asStateFlow()

    private val messageChannel = Channel<DuplicatesMessage>(Channel.BUFFERED)
    val messages: Flow<DuplicatesMessage> = messageChannel.receiveAsFlow()

    /** 探し直しの合図(値が変わるたびに探し直す) */
    private val refreshTrigger = MutableStateFlow(0)

    /** 統合・元に戻すの実行中は、続けて押された操作を受け付けない */
    private var busy = false

    init {
        viewModelScope.launch {
            // 厳しさを変えたら、その場で探し直す(探している途中なら取り消して新しい厳しさでやり直す)
            combine(appSettings.duplicateSensitivity, refreshTrigger) { sensitivity, _ -> sensitivity }
                .collectLatest { scan(it) }
        }
    }

    private suspend fun scan(sensitivity: DuplicateSensitivity) {
        _uiState.update { it.copy(isLoading = true, sensitivity = sensitivity, errorMessage = null) }
        try {
            val result = duplicateRepository.scan(sensitivity)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    duplicates = result.duplicates,
                    groups = result.groups,
                    indexIncomplete = !result.isComplete,
                    embeddedCount = result.embeddedCount,
                    totalCount = result.totalCount
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "重複・関連の検出に失敗")
            _uiState.update { it.copy(isLoading = false, errorMessage = "重複を調べられませんでした。もう一度お試しください。") }
        }
    }

    fun refresh() {
        refreshTrigger.update { it + 1 }
    }

    fun setSensitivity(sensitivity: DuplicateSensitivity) {
        viewModelScope.launch { appSettings.setDuplicateSensitivity(sensitivity) }
    }

    /** [match] の [keepId] の方を残し、もう一方を統合して消す。 */
    fun merge(match: DuplicateMatch, keepId: Long) {
        if (busy) return
        val keep = listOf(match.first, match.second).firstOrNull { it.id == keepId } ?: return
        val removeId = if (keepId == match.first.id) match.second.id else match.first.id
        busy = true
        viewModelScope.launch {
            try {
                val undo = duplicateRepository.merge(keepId, removeId)
                if (undo == null) {
                    messageChannel.send(DuplicatesMessage.Failed("すでに削除されているため、統合できませんでした。"))
                } else {
                    messageChannel.send(DuplicatesMessage.Merged(keep.title, undo))
                }
                dropFromResults(removeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "重複の統合に失敗")
                messageChannel.send(DuplicatesMessage.Failed("統合できませんでした。"))
            } finally {
                busy = false
            }
        }
    }

    /** 両方残す。この組は次から候補に出さない。 */
    fun keepBoth(match: DuplicateMatch) {
        viewModelScope.launch {
            try {
                duplicateRepository.keepBoth(match.pair)
                _uiState.update { state -> state.copy(duplicates = state.duplicates.filterNot { it.pair == match.pair }) }
                messageChannel.send(DuplicatesMessage.KeptBoth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "「両方残す」の保存に失敗")
                messageChannel.send(DuplicatesMessage.Failed("保存できませんでした。"))
            }
        }
    }

    fun undoMerge(undo: MergeUndo) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                duplicateRepository.undoMerge(undo)
                messageChannel.send(DuplicatesMessage.UndoDone)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "統合の取り消しに失敗")
                messageChannel.send(DuplicatesMessage.Failed("元に戻せませんでした。"))
            } finally {
                busy = false
            }
        }
    }

    /** 消したブックマークを含む候補・まとまりを結果から外す(探し直さずに、画面をすぐ更新する)。 */
    private fun dropFromResults(removedId: Long) {
        _uiState.update { state ->
            state.copy(
                duplicates = state.duplicates.filter { removedId !in it.pair },
                groups = state.groups.mapNotNull { group ->
                    val members = group.bookmarks.filter { it.id != removedId }
                    when {
                        members.size == group.bookmarks.size -> group
                        members.size < DuplicateDetector.MIN_GROUP_SIZE -> null
                        else -> group.copy(bookmarks = members)
                    }
                }
            )
        }
    }
}

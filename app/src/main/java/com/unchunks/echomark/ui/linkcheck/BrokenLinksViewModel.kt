package com.unchunks.echomark.ui.linkcheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.linkcheck.LinkCheckState
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.DeadLink
import com.unchunks.echomark.domain.repository.LinkCheckRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class BrokenLinksUiState(
    val isLoading: Boolean = true,
    /** 夜間にリンク切れを確認する設定 */
    val enabled: Boolean = true,
    val links: List<DeadLink> = emptyList(),
    /** 今すぐ確認している最中のブックマークの ID */
    val checkingIds: Set<Long> = emptySet(),
    val errorMessage: String? = null
)

/** リンク切れの画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface BrokenLinksMessage {
    /** 「確認しない」にした(元に戻せる) */
    data class Muted(val bookmarkId: Long, val title: String) : BrokenLinksMessage

    /** 確認した。[state] は結果(通信できなかったときは null) */
    data class Checked(val state: LinkCheckState?) : BrokenLinksMessage
}

@HiltViewModel
class BrokenLinksViewModel @Inject constructor(
    private val repository: LinkCheckRepository,
    private val appSettings: AppSettingsRepository
) : ViewModel() {

    private val messageChannel = Channel<BrokenLinksMessage>(Channel.BUFFERED)
    val messages: Flow<BrokenLinksMessage> = messageChannel.receiveAsFlow()

    private val checkingIds = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<BrokenLinksUiState> =
        combine(repository.observeDeadLinks(), appSettings.linkCheckEnabled, checkingIds) { links, enabled, checking ->
            BrokenLinksUiState(isLoading = false, enabled = enabled, links = links, checkingIds = checking)
        }
            .catch { e ->
                if (e is CancellationException) throw e
                Timber.w(e, "リンク切れの一覧の読み込みに失敗")
                emit(BrokenLinksUiState(isLoading = false, errorMessage = "リンク切れの一覧を読み込めませんでした。"))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrokenLinksUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setLinkCheckEnabled(enabled) }
    }

    /** 今すぐ確認し直す。開けたら一覧から消える */
    fun recheck(link: DeadLink) {
        val id = link.bookmark.id
        if (id in checkingIds.value) return
        viewModelScope.launch {
            checkingIds.value += id
            try {
                messageChannel.send(BrokenLinksMessage.Checked(repository.checkNow(id)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "リンクの確認に失敗")
                messageChannel.send(BrokenLinksMessage.Checked(null))
            } finally {
                checkingIds.value -= id
            }
        }
    }

    /** このリンクの確認をやめる(一覧から外す)。Snackbar の「元に戻す」で [unmute] できるよう知らせる */
    fun mute(link: DeadLink) {
        viewModelScope.launch {
            repository.setMuted(link.bookmark.id, true)
            messageChannel.send(BrokenLinksMessage.Muted(link.bookmark.id, link.bookmark.title))
        }
    }

    fun unmute(bookmarkId: Long) {
        viewModelScope.launch { repository.setMuted(bookmarkId, false) }
    }
}

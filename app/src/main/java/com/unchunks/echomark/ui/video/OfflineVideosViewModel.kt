package com.unchunks.echomark.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.video.OfflineVideoRepository
import com.unchunks.echomark.domain.video.SavedVideoListItem
import com.unchunks.echomark.domain.video.VideoDownloadScheduler
import com.unchunks.echomark.domain.video.VideoDownloadSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 「オフラインの動画」の画面の状態。
 * @property usedBytes 保存した動画の合計
 * @property freeBytes 端末の空き容量(分からなければ null)
 */
data class OfflineVideosUiState(
    val isLoading: Boolean = true,
    val settings: VideoDownloadSettings = VideoDownloadSettings(),
    val items: List<SavedVideoListItem> = emptyList(),
    val usedBytes: Long = 0L,
    val freeBytes: Long? = null
)

/** 保存した動画の一覧・削除と、動画のダウンロードの設定(Wi-Fi のときだけ・上限) */
@HiltViewModel
class OfflineVideosViewModel @Inject constructor(
    private val repository: OfflineVideoRepository,
    private val downloadScheduler: VideoDownloadScheduler,
    private val appSettings: AppSettingsRepository
) : ViewModel() {

    private val freeBytes = MutableStateFlow<Long?>(null)

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<OfflineVideosUiState> =
        combine(appSettings.videoDownloadSettings, repository.observeSavedVideos(), repository.observeVideoBytes(), freeBytes) {
                settings, items, used, free ->
            OfflineVideosUiState(isLoading = false, settings = settings, items = items, usedBytes = used, freeBytes = free)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineVideosUiState())

    init {
        refreshFreeBytes()
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { appSettings.setVideoDownloadWifiOnly(enabled) }
    }

    fun setMaxTotalBytes(bytes: Long) {
        viewModelScope.launch { appSettings.setVideoDownloadMaxTotalBytes(bytes) }
    }

    /** 1件の動画のファイルを消す(ブックマークの字幕・要約・サムネイルは残す) */
    fun delete(item: SavedVideoListItem) = run("削除できませんでした") {
        downloadScheduler.cancel(item.bookmarkId)
        repository.removeVideo(item.bookmarkId)
        messageChannel.send("「${item.title}」の動画を削除しました")
    }

    /** すべての動画のファイルを消す(ダウンロードの途中のものも止めて消す) */
    fun deleteAll() = run("削除できませんでした") {
        downloadScheduler.cancelAll()
        repository.removeAllVideos()
        messageChannel.send("保存した動画をすべて削除しました")
    }

    private fun refreshFreeBytes() {
        viewModelScope.launch { freeBytes.value = runCatching { repository.freeBytes() }.getOrNull() }
    }

    private fun run(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "保存した動画を消せなかった")
                messageChannel.send(failure)
            } finally {
                refreshFreeBytes()
            }
        }
    }
}

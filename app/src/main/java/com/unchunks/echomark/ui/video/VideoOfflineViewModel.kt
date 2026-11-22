package com.unchunks.echomark.ui.video

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.network.NetworkGate
import com.unchunks.echomark.domain.video.OfflineVideo
import com.unchunks.echomark.domain.video.OfflineVideoRepository
import com.unchunks.echomark.domain.video.VideoOfflineScheduler
import com.unchunks.echomark.domain.video.VideoOfflineWork
import com.unchunks.echomark.domain.video.VideoDownloadFailure
import com.unchunks.echomark.domain.video.VideoDownloadScheduler
import com.unchunks.echomark.domain.video.VideoDownloadWork
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import kotlinx.coroutines.flow.first
import com.unchunks.echomark.ui.detail.BookmarkDetailViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * 詳細画面の「動画のオフライン保存」の状態。
 * @property saved 保存した記録(保存していなければ null)
 * @property thumbnailFile 保存したサムネイルのファイル(無ければ null。画面はリンク先の画像を出す)
 * @property running 保存の作業が動いている(順番待ちを含む)
 * @property download 動画のファイルのダウンロードの作業(一度も積んでいなければ null)
 * @property videoFile 保存した動画のファイル(無ければ null)
 * @property wifiOnly 動画のファイルは Wi-Fi のときだけ落とす設定
 */
data class VideoOfflineUiState(
    val saved: OfflineVideo? = null,
    val thumbnailFile: File? = null,
    val work: VideoOfflineWork? = null,
    val download: VideoDownloadWork? = null,
    val videoFile: File? = null,
    val wifiOnly: Boolean = true
) {
    val running: Boolean get() = work is VideoOfflineWork.Running

    /** 動画のファイルを落としている(順番待ち・Wi-Fi 待ちを含む) */
    val downloading: Boolean get() = download is VideoDownloadWork.Waiting || download is VideoDownloadWork.Downloading
}

/** 動画のブックマークの、字幕・要約・サムネイルのオフライン保存(保存・やめる・削除)。作業は WorkManager([VideoOfflineScheduler])が行う */
@HiltViewModel
class VideoOfflineViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OfflineVideoRepository,
    private val scheduler: VideoOfflineScheduler,
    private val downloadScheduler: VideoDownloadScheduler,
    private val appSettings: AppSettingsRepository,
    private val gate: NetworkGate,
    dispatcherProvider: DispatcherProvider
) : ViewModel() {

    // 詳細画面と同じ引数(ルート "bookmark/{bookmarkId}"、2 画面表示では同じキー)
    private val bookmarkId: Long = checkNotNull(savedStateHandle.get<Long>(BookmarkDetailViewModel.ARG_BOOKMARK_ID))

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<VideoOfflineUiState> =
        combine(
            repository.observe(bookmarkId),
            scheduler.observe(bookmarkId),
            downloadScheduler.observe(bookmarkId),
            appSettings.videoDownloadSettings
        ) { saved, work, download, settings ->
            VideoOfflineUiState(
                saved = saved,
                thumbnailFile = repository.fileOf(saved?.thumbnailPath),
                work = work,
                download = download,
                videoFile = repository.videoFileOf(saved?.video?.path),
                wifiOnly = settings.wifiOnly
            )
        }
            // ファイルがあるかを確かめる(ディスクを読む)ので、メインスレッドの外で行う
            .flowOn(dispatcherProvider.io)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoOfflineUiState())

    init {
        // この画面で見ていた作業が終わったら結果を知らせる(前に終わった作業の結果は出さない)
        viewModelScope.launch {
            var wasRunning = false
            scheduler.observe(bookmarkId).collect { work ->
                if (wasRunning) resultMessage(work)?.let { messageChannel.send(it) }
                wasRunning = work is VideoOfflineWork.Running
            }
        }
        viewModelScope.launch {
            var wasDownloading = false
            downloadScheduler.observe(bookmarkId).collect { work ->
                if (wasDownloading) downloadResultMessage(work)?.let { messageChannel.send(it) }
                wasDownloading = work is VideoDownloadWork.Waiting || work is VideoDownloadWork.Downloading
            }
        }
    }

    /** 動画のファイルを落とし始める(Wi-Fi のときだけの設定なら、Wi-Fi につながるまで待つ) */
    fun downloadVideo() {
        if (gate.isOffline()) {
            messageChannel.trySend(OFFLINE_MESSAGE)
            return
        }
        viewModelScope.launch {
            downloadScheduler.enqueue(bookmarkId, wifiOnly = appSettings.videoDownloadSettings.first().wifiOnly)
        }
    }

    /** 落とすのをやめる(途中まで落としたファイルも消す。前に保存した動画は残す) */
    fun cancelDownload() {
        downloadScheduler.cancel(bookmarkId)
        viewModelScope.launch { runCatching { repository.discardPartialVideo(bookmarkId) } }
    }

    /** 保存した動画のファイルを消す(字幕・要約・サムネイルは残す) */
    fun removeVideo() {
        viewModelScope.launch {
            try {
                downloadScheduler.cancel(bookmarkId)
                repository.removeVideo(bookmarkId)
                messageChannel.send("保存した動画を削除しました")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "保存した動画を消せなかった")
                messageChannel.send("保存した動画を削除できませんでした")
            }
        }
    }

    /** 保存を始める(保存し直しも)。オフラインモードの間は始めずに理由を知らせる */
    fun save() {
        if (gate.isOffline()) {
            messageChannel.trySend(OFFLINE_MESSAGE)
            return
        }
        scheduler.enqueue(bookmarkId)
    }

    fun cancel() = scheduler.cancel(bookmarkId)

    /** 保存したサムネイルと記録を消す(本文の字幕・要約は残す) */
    fun remove() {
        viewModelScope.launch {
            try {
                scheduler.cancel(bookmarkId)
                downloadScheduler.cancel(bookmarkId)
                repository.remove(bookmarkId)
                messageChannel.send("オフライン用の保存を削除しました")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "動画のオフライン保存を消せなかった")
                messageChannel.send("オフライン用の保存を削除できませんでした")
            }
        }
    }

    companion object {
        const val OFFLINE_MESSAGE = "オフラインモードの間は保存できません。設定の「オフラインモード」をオフにしてください"

        internal fun resultMessage(work: VideoOfflineWork?): String? = when (work) {
            is VideoOfflineWork.Finished -> when {
                work.hasTranscript && work.hasThumbnail -> "字幕とサムネイルを保存しました"
                work.hasThumbnail -> "サムネイルを保存しました(この動画の字幕は取れませんでした)"
                work.hasTranscript -> "字幕を保存しました(サムネイルは保存できませんでした)"
                else -> "字幕もサムネイルも取れませんでした。説明文と要約は通信せずに読めます"
            }
            is VideoOfflineWork.Failed ->
                if (work.offlineMode) OFFLINE_MESSAGE else "保存できませんでした。通信できるか確かめてください"
            is VideoOfflineWork.Cancelled -> "保存をやめました"
            is VideoOfflineWork.Running, null -> null
        }

        internal fun downloadResultMessage(work: VideoDownloadWork?): String? = when (work) {
            VideoDownloadWork.Finished -> "動画を保存しました。通信できなくても再生できます"
            is VideoDownloadWork.Failed -> failureMessage(work.reason)
            VideoDownloadWork.Cancelled -> "動画の保存をやめました"
            VideoDownloadWork.Waiting, is VideoDownloadWork.Downloading, null -> null
        }

        internal fun failureMessage(reason: VideoDownloadFailure): String = when (reason) {
            VideoDownloadFailure.OFFLINE_MODE -> OFFLINE_MESSAGE
            VideoDownloadFailure.NOT_ALLOWED -> "この動画は、投稿者がダウンロードを許していないため保存できません"
            VideoDownloadFailure.NOT_VIDEO -> "リンク先が動画のファイルではなかったため保存できません"
            VideoDownloadFailure.OVER_LIMIT -> "保存する動画の上限を超えるため保存できません。設定の「オフラインの動画」で上限を変えるか、ほかの動画を削除してください"
            VideoDownloadFailure.NO_SPACE -> "端末の空き容量が足りないため保存できません"
            VideoDownloadFailure.NETWORK -> "通信に失敗したため保存できませんでした。もう一度試してください"
            VideoDownloadFailure.NOT_TARGET -> "この動画は保存できません"
        }
    }
}

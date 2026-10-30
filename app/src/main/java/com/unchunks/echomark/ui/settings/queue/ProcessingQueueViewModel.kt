package com.unchunks.echomark.ui.settings.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.ProcessingQueue
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.domain.repository.ProcessingStatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProcessingQueueUiState(
    val isLoading: Boolean = true,
    val queue: ProcessingQueue = ProcessingQueue(),
    /** 夜間にまとめて処理する設定など(処理待ちの説明に使う) */
    val settings: ProcessingSettings = ProcessingSettings(),
    /** 段・中身の種類ごとの平均の所要時間(残り時間の目安に使う) */
    val stageAverages: Map<StageDurationKey, Long> = emptyMap()
)

/** 処理の状況の画面(#65)。処理中・処理待ち・最近の失敗を出し、やめる・今すぐ処理する・再試行を受け付ける。 */
@HiltViewModel
class ProcessingQueueViewModel @Inject constructor(
    private val repository: BookmarkRepository,
    appSettings: AppSettingsRepository,
    processingStats: ProcessingStatsRepository
) : ViewModel() {

    val uiState: StateFlow<ProcessingQueueUiState> = combine(
        repository.observeProcessingQueue(),
        appSettings.processingSettings,
        processingStats.averageStageDurations.catch { emit(emptyMap()) }.onStart { emit(emptyMap()) }
    ) { queue, settings, averages ->
        ProcessingQueueUiState(isLoading = false, queue = queue, settings = settings, stageAverages = averages)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingQueueUiState())

    /** 処理をやめる(処理待ち・処理中のもの) */
    fun cancel(id: Long) {
        viewModelScope.launch { repository.cancelProcessing(listOf(id)) }
    }

    /** 処理待ちのものを、順番を飛ばして今すぐ処理する */
    fun processNow(id: Long) {
        viewModelScope.launch { repository.processNow(id) }
    }

    /** 失敗したもの(やめたものも)をもう一度処理する */
    fun retry(id: Long) {
        viewModelScope.launch { repository.reprocess(id) }
    }
}

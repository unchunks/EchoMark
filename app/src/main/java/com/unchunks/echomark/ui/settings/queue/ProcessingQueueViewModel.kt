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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    val stageAverages: Map<StageDurationKey, Long> = emptyMap(),
    /** 処理中のものの、実行中の段の進み具合(0〜1。報告している段だけ) */
    val stageProgress: Map<Long, Float> = emptyMap()
)

/** 処理の状況の画面(#65)。処理中・処理待ち・最近の失敗を出し、やめる・今すぐ処理する・再試行を受け付ける。 */
@HiltViewModel
class ProcessingQueueViewModel @Inject constructor(
    private val repository: BookmarkRepository,
    appSettings: AppSettingsRepository,
    processingStats: ProcessingStatsRepository
) : ViewModel() {

    private val queue = repository.observeProcessingQueue()

    /** 処理中のものの、実行中の段の進み具合(報告している段だけ) */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val stageProgress: Flow<Map<Long, Float>> = queue
        .map { queue -> queue.running.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> repository.observeStageProgress(id).map { id to it } }) { pairs ->
                    pairs.mapNotNull { (id, progress) -> progress?.let { id to it } }.toMap()
                }
            }
        }

    val uiState: StateFlow<ProcessingQueueUiState> = combine(
        queue,
        appSettings.processingSettings,
        processingStats.averageStageDurations.catch { emit(emptyMap()) }.onStart { emit(emptyMap()) },
        stageProgress
    ) { queue, settings, averages, progress ->
        ProcessingQueueUiState(
            isLoading = false,
            queue = queue,
            settings = settings,
            stageAverages = averages,
            stageProgress = progress
        )
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

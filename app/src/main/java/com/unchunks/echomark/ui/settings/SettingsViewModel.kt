package com.unchunks.echomark.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.data.ai.model.ModelSpec
import com.unchunks.echomark.data.ai.model.ModelSpecs
import com.unchunks.echomark.data.ai.model.ModelState
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.worker.RediscoverDigestScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

/** 設定画面に表示するモデル1件分。 */
data class ModelItemUiState(
    val spec: ModelSpec,
    val state: ModelState
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val appSettings: AppSettingsRepository,
    private val workManager: WorkManager
) : ViewModel() {

    val models: Flow<List<ModelItemUiState>> = modelManager.states.map { states ->
        ModelSpecs.all.map { spec ->
            ModelItemUiState(spec, states[spec.id] ?: ModelState.NotDownloaded)
        }
    }

    val llmBackend: StateFlow<LlmBackend> = appSettings.llmBackend
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmBackend.LOCAL)

    val rediscover: StateFlow<RediscoverSettings> = appSettings.rediscoverSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RediscoverSettings())

    fun download(spec: ModelSpec) = modelManager.startDownload(spec)

    fun cancel(spec: ModelSpec) = modelManager.cancelDownload(spec)

    fun delete(spec: ModelSpec) = modelManager.delete(spec)

    fun setLlmBackend(backend: LlmBackend) {
        viewModelScope.launch { appSettings.setLlmBackend(backend) }
    }

    /** 再発見通知のオン/オフ。オンなら現在の曜日・時刻でスケジュールし、オフなら取り消す。 */
    fun setRediscoverEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setRediscoverEnabled(enabled)
            applySchedule()
        }
    }

    fun setRediscoverSchedule(dayOfWeek: DayOfWeek, hour: Int, minute: Int) {
        viewModelScope.launch {
            appSettings.setRediscoverSchedule(dayOfWeek, hour, minute)
            applySchedule()
        }
    }

    private suspend fun applySchedule() {
        val settings = appSettings.rediscoverSettings.first()
        if (settings.enabled) {
            RediscoverDigestScheduler.schedule(workManager, settings.dayOfWeek, settings.hour, settings.minute)
        } else {
            RediscoverDigestScheduler.cancel(workManager)
        }
    }
}

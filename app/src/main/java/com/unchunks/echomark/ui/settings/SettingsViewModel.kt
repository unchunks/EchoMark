package com.unchunks.echomark.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.worker.RediscoverDigestScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val workManager: WorkManager
) : ViewModel() {

    val llmBackend: StateFlow<LlmBackend> = appSettings.llmBackend
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmBackend.LOCAL)

    val rediscover: StateFlow<RediscoverSettings> = appSettings.rediscoverSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RediscoverSettings())

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

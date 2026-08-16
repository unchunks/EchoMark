package com.unchunks.echomark.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.unchunks.echomark.domain.model.NotificationSettings
import com.unchunks.echomark.domain.repository.NotificationSettingsRepository
import com.unchunks.echomark.worker.ReviewDigestScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: NotificationSettingsRepository,
    private val workManager: WorkManager
) : ViewModel() {

    val uiState: StateFlow<NotificationSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettings())

    fun setEnabled(enabled: Boolean) {
        val current = uiState.value
        viewModelScope.launch {
            settingsRepository.setEnabled(enabled)
            if (enabled) {
                ReviewDigestScheduler.schedule(workManager, current.hour, current.minute, ExistingPeriodicWorkPolicy.REPLACE)
            } else {
                ReviewDigestScheduler.cancel(workManager)
            }
        }
    }

    fun setTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setTime(hour, minute)
            if (uiState.value.enabled) {
                ReviewDigestScheduler.schedule(workManager, hour, minute, ExistingPeriodicWorkPolicy.REPLACE)
            }
        }
    }
}

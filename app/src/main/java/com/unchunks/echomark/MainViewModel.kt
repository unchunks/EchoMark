package com.unchunks.echomark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 起動直後に必要な設定。読み込みが終わるまではスプラッシュを出したままにする。 */
sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(
        val themeMode: ThemeMode,
        val dynamicColor: Boolean,
        val onboardingCompleted: Boolean
    ) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    appSettings: AppSettingsRepository
) : ViewModel() {

    // スプラッシュの保持判定は Compose の購読前から見るため、即座に読み込みを始める
    val uiState: StateFlow<MainUiState> = combine(
        appSettings.themeMode,
        appSettings.dynamicColor,
        appSettings.onboardingCompleted
    ) { themeMode, dynamicColor, onboardingCompleted ->
        MainUiState.Ready(themeMode, dynamicColor, onboardingCompleted)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState.Loading)
}

/** テーマ設定と端末の設定から、暗い配色にするかを決める。 */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

package com.unchunks.echomark.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.worker.RediscoverScheduleController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** オンボーディングの「AI の準備」で選んだもの。 */
enum class AiSetupChoice { LOCAL, API, LATER }

/** オンボーディングを終えた後に開く画面。 */
enum class OnboardingExit {
    /** ブックマーク一覧 */
    LIST,

    /** AI 設定(端末内モデルの取り込み・API キーの入力) */
    AI_SETTINGS
}

data class OnboardingUiState(
    val aiChoice: AiSetupChoice? = null,
    val rediscover: RediscoverSettings = RediscoverSettings()
) {
    /** 最後のページのボタンで、AI 設定へ進むか */
    val exit: OnboardingExit
        get() = if (aiChoice == AiSetupChoice.LOCAL || aiChoice == AiSetupChoice.API) {
            OnboardingExit.AI_SETTINGS
        } else {
            OnboardingExit.LIST
        }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val scheduleController: RediscoverScheduleController
) : ViewModel() {

    private val aiChoice = MutableStateFlow<AiSetupChoice?>(null)

    val uiState: StateFlow<OnboardingUiState> = combine(aiChoice, appSettings.rediscoverSettings) { choice, rediscover ->
        OnboardingUiState(aiChoice = choice, rediscover = rediscover)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    /** AI の実行場所を選ぶ。「あとで」なら設定は変えない。 */
    fun chooseAi(choice: AiSetupChoice) {
        aiChoice.value = choice
        viewModelScope.launch {
            when (choice) {
                AiSetupChoice.LOCAL -> appSettings.setLlmBackend(LlmBackend.LOCAL)
                AiSetupChoice.API -> appSettings.setLlmBackend(LlmBackend.API)
                AiSetupChoice.LATER -> Unit
            }
        }
    }

    /** 再発見通知をオンにする(通知権限は画面側で確認済み)。既定の曜日・時刻で予定を登録する。 */
    fun enableRediscover() {
        viewModelScope.launch {
            appSettings.setRediscoverEnabled(true)
            scheduleController.apply(appSettings.rediscoverSettings.first())
        }
    }

    /** オンボーディングを終える(スキップを含む)。次回からは表示しない。 */
    fun complete() {
        viewModelScope.launch { appSettings.setOnboardingCompleted(true) }
    }
}

package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.ui.onboarding.AiSetupChoice
import com.unchunks.echomark.ui.onboarding.OnboardingActions
import com.unchunks.echomark.ui.onboarding.OnboardingContent
import com.unchunks.echomark.ui.onboarding.OnboardingUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** オンボーディングの各ページ(スマートフォン1画面分の高さで撮る)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Composable
    private fun Page(page: Int, state: OnboardingUiState = OnboardingUiState(), notificationDenied: Boolean = false) {
        Box(Modifier.height(800.dp)) {
            OnboardingContent(
                uiState = state,
                actions = OnboardingActions(),
                notificationDenied = notificationDenied,
                initialPage = page
            )
        }
    }

    @Test
    fun welcome() = screenshot.captureLightDark("onboarding_1_welcome") { Page(0) }

    @Test
    fun save() = screenshot.captureLightDark("onboarding_2_save") { Page(1) }

    @Test
    fun aiSetupNotChosen() = screenshot.captureLightDark("onboarding_3_ai") { Page(2) }

    @Test
    fun aiSetupLocalChosen() = screenshot.captureLightDark("onboarding_3_ai_local") {
        Page(2, OnboardingUiState(aiChoice = AiSetupChoice.LOCAL))
    }

    /** AI を「端末内」に決めた後の最後のページ(ボタンが「AI の設定へ進む」になる) */
    @Test
    fun rediscoverAfterChoosingAi() = screenshot.captureLightDark("onboarding_4_rediscover") {
        Page(3, OnboardingUiState(aiChoice = AiSetupChoice.LOCAL))
    }

    @Test
    fun rediscoverEnabled() = screenshot.captureLightDark("onboarding_4_rediscover_enabled") {
        Page(3, OnboardingUiState(aiChoice = AiSetupChoice.LATER, rediscover = RediscoverSettings(enabled = true)))
    }

    @Test
    fun rediscoverDenied() = screenshot.captureLightDark("onboarding_4_rediscover_denied") {
        Page(3, notificationDenied = true)
    }
}

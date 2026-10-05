package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.ui.settings.AiSummary
import com.unchunks.echomark.ui.settings.SettingsActions
import com.unchunks.echomark.ui.settings.SettingsContent
import com.unchunks.echomark.ui.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek

/** 設定画面の各セクション(縦長の画面で全体を撮る)。ダイアログは [SettingsDialogScreenshotTest]。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class SettingsScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val readyState = SettingsUiState(
        isLoaded = true,
        ai = AiSummary(
            backend = LlmBackend.API,
            apiProvider = ApiProvider.CLAUDE,
            apiModel = "claude-opus-5-5",
            apiKeyConfigured = true
        ),
        themeMode = ThemeMode.SYSTEM,
        dynamicColor = false,
        rediscover = RediscoverSettings(enabled = true, dayOfWeek = DayOfWeek.SUNDAY, hour = 20, minute = 0),
        storage = StorageUsage(databaseBytes = 1_300_000, embeddingBytes = 420_000, modelBytes = 556_000_000, attachmentBytes = 84_000_000),
        versionLabel = "1.0 (1)"
    )

    @Composable
    private fun Screen(state: SettingsUiState, height: Int, notificationsBlocked: Boolean = false) {
        Box(Modifier.height(height.dp)) {
            SettingsContent(
                uiState = state,
                actions = SettingsActions(onOpenOnboarding = {}),
                notificationsBlocked = notificationsBlocked
            )
        }
    }

    /** スマートフォン1画面分(先頭) */
    @Test
    fun settingsTop() = screenshot.captureLightDark("settings_top") {
        Screen(readyState, height = 820)
    }

    /** 全セクション(縦長で全体を撮る) */
    @Test
    fun settingsAll() = screenshot.captureLightDark("settings_all") {
        Screen(readyState, height = 2000)
    }

    /** AI 未設定・通知が拒否されている・ストレージ計算中 */
    @Test
    fun settingsNeedsSetup() = screenshot.captureLightDark("settings_needs_setup") {
        Screen(
            readyState.copy(
                ai = AiSummary(backend = LlmBackend.LOCAL, localModelName = null),
                themeMode = ThemeMode.DARK,
                dynamicColor = true,
                storage = null
            ),
            height = 1300,
            notificationsBlocked = true
        )
    }

    @Test
    fun loading() = screenshot.captureLightDark("settings_loading") {
        Screen(SettingsUiState(), height = 400)
    }
}

package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.backup.AutoBackupSettings
import com.unchunks.echomark.domain.lock.AppLockDelay
import com.unchunks.echomark.domain.lock.AppLockSettings
import com.unchunks.echomark.domain.model.SummaryFormat
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.model.SummaryStyle
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.testing.aiTasks
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
            tasks = aiTasks(LlmBackend.API, ApiProvider.CLAUDE),
            configuredProviders = setOf(ApiProvider.CLAUDE)
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
        Screen(readyState, height = 2500)
    }

    /** 自動バックアップをオンにしていて、保存先を使えなくなっている(「データ」の欄) */
    @Test
    fun settingsAutoBackup() = screenshot.captureLightDark("settings_auto_backup") {
        Screen(
            readyState.copy(
                autoBackup = AutoBackupSettings(
                    enabled = true, folderUri = "content://tree/x", folderLabel = "EchoMark のバックアップ", keep = 5,
                    lastSuccessAt = 1_700_000_000_000L, lastError = "保存先のフォルダを使えなくなりました。設定の「自動バックアップ」で、保存先を選び直してください"
                )
            ),
            height = 3000
        )
    }

    /** アプリのロックと、内容を隠す設定をオンにしている(「ロック」の欄) */
    @Test
    fun settingsAppLock() = screenshot.captureLightDark("settings_app_lock") {
        Screen(
            readyState.copy(appLock = AppLockSettings(enabled = true, delay = AppLockDelay.MINUTE_1, hideContent = true)),
            height = 1300
        )
    }

    /** オフラインモードをオンにしている(「通信」の欄) */
    @Test
    fun settingsOfflineMode() = screenshot.captureLightDark("settings_offline_mode") {
        Screen(readyState.copy(offlineMode = true), height = 1100)
    }

    /** 処理の設定(充電中だけ・夜間にまとめて処理する・同時に処理する数) */
    @Test
    fun settingsProcessing() = screenshot.captureLightDark("settings_processing") {
        Screen(
            readyState.copy(
                processing = ProcessingSettings(chargingOnly = true, nightlyBatch = true, nightlyHour = 2, maxConcurrent = 1)
            ),
            height = 1100
        )
    }

    /** 要約の言語と書き方を変えている */
    @Test
    fun settingsSummary() = screenshot.captureLightDark("settings_summary") {
        Screen(
            readyState.copy(summaryStyle = SummaryStyle(SummaryLanguage.ENGLISH, SummaryFormat.BULLETS)),
            height = 1300
        )
    }

    /** AI 未設定・通知が拒否されている・ストレージ計算中 */
    @Test
    fun settingsNeedsSetup() = screenshot.captureLightDark("settings_needs_setup") {
        Screen(
            readyState.copy(
                ai = AiSummary(tasks = aiTasks(LlmBackend.LOCAL), localModelName = null),
                themeMode = ThemeMode.DARK,
                dynamicColor = true,
                storage = null
            ),
            height = 1300,
            notificationsBlocked = true
        )
    }

    /** 用途ごとに違う AI を選んでいる */
    @Test
    fun settingsMixedAi() = screenshot.captureLightDark("settings_mixed_ai") {
        Screen(
            readyState.copy(
                ai = AiSummary(
                    tasks = aiTasks(LlmBackend.API, ApiProvider.CLAUDE) +
                        (AiTask.TAGGING to aiTasks(LlmBackend.LOCAL).getValue(AiTask.TAGGING)),
                    localModelName = "gemma3-1b-it-int4",
                    configuredProviders = setOf(ApiProvider.CLAUDE)
                )
            ),
            height = 820
        )
    }

    @Test
    fun loading() = screenshot.captureLightDark("settings_loading") {
        Screen(SettingsUiState(), height = 400)
    }
}

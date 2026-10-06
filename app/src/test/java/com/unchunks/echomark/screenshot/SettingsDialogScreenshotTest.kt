package com.unchunks.echomark.screenshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.repository.BackupImportSummary
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.ui.settings.DataOperationDialog
import com.unchunks.echomark.ui.settings.DataOperationState
import com.unchunks.echomark.ui.settings.DeleteConfirmDialog
import com.unchunks.echomark.ui.settings.DeleteFinalDialog
import com.unchunks.echomark.ui.settings.LicensesDialog
import com.unchunks.echomark.ui.settings.PrivacyDialog
import com.unchunks.echomark.ui.settings.ThemeModeDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 設定画面のダイアログ(テーマ・削除の二重確認・プライバシー・ライセンス・バックアップの進行と結果)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class SettingsDialogScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun themeDialog() = screenshot.captureDialogLightDark("settings_dialog_theme") {
        ThemeModeDialog(selected = ThemeMode.SYSTEM, onSelect = {}, onDismiss = {})
    }

    @Test
    fun deleteConfirmDialog() = screenshot.captureDialogLightDark("settings_dialog_delete_confirm") {
        DeleteConfirmDialog(resetSettings = true, onResetSettingsChange = {}, onNext = {}, onDismiss = {})
    }

    @Test
    fun deleteFinalDialog() = screenshot.captureDialogLightDark("settings_dialog_delete_final") {
        DeleteFinalDialog(resetSettings = false, onConfirm = {}, onDismiss = {})
    }

    @Test
    fun privacyDialog() = screenshot.captureDialogLightDark("settings_dialog_privacy") {
        PrivacyDialog(backends = setOf(LlmBackend.LOCAL), onDismiss = {})
    }

    @Test
    fun licensesDialog() = screenshot.captureDialogLightDark("settings_dialog_licenses") {
        LicensesDialog(onDismiss = {})
    }

    @Test
    fun importResultDialog() = screenshot.captureDialogLightDark("settings_dialog_import_result") {
        DataOperationDialog(
            state = DataOperationState.Imported(
                BackupImportSummary(
                    bookmarksAdded = 42, bookmarksSkipped = 3, tagsAdded = 12,
                    conversationsAdded = 5, conversationsSkipped = 1, messagesAdded = 30, invalidRecords = 2
                )
            ),
            onDismiss = {}
        )
    }

    @Test
    fun importFailedDialog() = screenshot.captureDialogLightDark("settings_dialog_import_failed") {
        DataOperationDialog(
            state = DataOperationState.Failed(
                "読み込めませんでした",
                "新しいバージョンのアプリで作られたバックアップです。アプリを更新してから読み込んでください"
            ),
            onDismiss = {}
        )
    }

    @Test
    fun runningDialog() = screenshot.captureDialogLightDark("settings_dialog_running") {
        DataOperationDialog(state = DataOperationState.Running("バックアップを読み込んでいます…"), onDismiss = {})
    }
}

package com.unchunks.echomark.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import com.unchunks.echomark.data.local.encryption.DatabaseEncryptionState
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED
import com.unchunks.echomark.ui.components.SectionHeader

internal fun LazyListScope.preferenceItems(
    uiState: SettingsUiState,
    actions: SettingsActions,
    notificationsBlocked: Boolean,
    dynamicColorAvailable: Boolean,
    openDialog: (SettingsDialog) -> Unit
) {
    item("ai-heading") { SectionHeader("AI") }
    item("ai") { SettingsAiCard(uiState.ai, actions.onOpenAiSettings) }

    item {
        SettingsItem(
            title = "カテゴリ",
            icon = Icons.Outlined.Category,
            summary = "AI の分類に使うカテゴリを作る・説明を見る",
            onClick = actions.onOpenCategories
        )
    }

    item("appearance-heading") { SectionHeader("表示") }
    item("theme") {
        SettingsItem(
            title = "テーマ",
            icon = Icons.Outlined.BrightnessMedium,
            summary = uiState.themeMode.label,
            onClick = { openDialog(SettingsDialog.THEME) }
        )
    }
    if (dynamicColorAvailable) {
        item("dynamic-color") {
            SettingsSwitchItem(
                title = "ダイナミックカラー",
                icon = Icons.Outlined.Palette,
                summary = "壁紙の色をアプリの配色に使います",
                checked = uiState.dynamicColor,
                onCheckedChange = actions.onDynamicColorChange
            )
        }
    }

    item("notifications-heading") { SectionHeader("通知") }
    item("rediscover") {
        SettingsSwitchItem(
            title = "再発見通知",
            icon = Icons.Outlined.NotificationsActive,
            summary = "週に1回、忘れかけたブックマークをお知らせ",
            checked = uiState.rediscover.enabled,
            onCheckedChange = actions.onRediscoverToggle
        )
    }
    if (notificationsBlocked) {
        item("notification-permission") {
            SettingsNotice(
                text = "通知が許可されていません。端末の設定で EchoMark の通知をオンにしてください。",
                icon = Icons.Outlined.NotificationsOff,
                isWarning = true,
                action = {
                    TextButton(
                        onClick = actions.onOpenNotificationSettings,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { Text("通知の設定を開く") }
                }
            )
        }
    }
    if (uiState.rediscover.enabled) {
        item("notification-day") {
            SettingsItem(
                title = "曜日",
                icon = Icons.Outlined.CalendarMonth,
                summary = uiState.rediscover.dayOfWeek.fullLabel,
                onClick = { openDialog(SettingsDialog.DAY_OF_WEEK) }
            )
        }
        item("notification-time") {
            SettingsItem(
                title = "時刻",
                icon = Icons.Outlined.Schedule,
                summary = "%02d:%02d".format(uiState.rediscover.hour, uiState.rediscover.minute),
                onClick = { openDialog(SettingsDialog.TIME) }
            )
        }
    }

    processingSettingsItems(
        settings = uiState.processing,
        onChange = actions.onProcessingSettingsChange,
        onOpenDialog = openDialog,
        onOpenQueue = actions.onOpenProcessingQueue
    )

    // クラウドの AI を無効にしている間は、使用量の欄も出さない(CLOUD_AI_ENABLED。記録も無い)
    if (CLOUD_AI_ENABLED) apiUsageItems(uiState.apiUsage)

    summarySettingsItems(
        uiState.summaryStyle,
        onOpenDialog = openDialog,
        onChange = actions.onSummaryStyleChange
    )

    item { SectionHeader("通信") }
    item {
        SettingsSwitchItem(
            title = "オフラインモード",
            icon = Icons.Outlined.CloudOff,
            summary = OFFLINE_MODE_SUMMARY,
            checked = uiState.offlineMode,
            onCheckedChange = actions.onOfflineModeChange
        )
    }

    item { SectionHeader("ロック") }
    item {
        SettingsSwitchItem(
            title = "アプリのロック",
            icon = Icons.Outlined.Lock,
            summary = APP_LOCK_SUMMARY,
            checked = uiState.appLock.enabled,
            onCheckedChange = actions.onAppLockChange
        )
    }
    if (uiState.appLock.enabled) {
        item {
            SettingsItem(
                title = "ロックするまでの時間",
                icon = Icons.Outlined.Timer,
                summary = "アプリを離れてから${uiState.appLock.delay.label}",
                onClick = { openDialog(SettingsDialog.APP_LOCK_DELAY) }
            )
        }
    }
    databaseEncryptionItems(uiState.databaseEncryption, actions.onDismissDatabaseKeyLost)
    item {
        SettingsSwitchItem(
            title = "最近使ったアプリで内容を隠す",
            icon = Icons.Outlined.VisibilityOff,
            summary = "アプリの一覧に画面の内容を出しません。この設定の間は、スクリーンショットも撮れません",
            checked = uiState.appLock.hideContent,
            onCheckedChange = actions.onAppLockHideContentChange
        )
    }
}

internal fun LazyListScope.managementItems(
    uiState: SettingsUiState,
    actions: SettingsActions,
    openDialog: (SettingsDialog) -> Unit
) {
    item("data-heading") { SectionHeader("データ管理") }
    item("backup-notice") {
        SettingsNotice(
            text = "API キーと端末内モデルはバックアップに含まれません。JSON から復元する場合、ファイルのブックマークは要約とメモだけが復元されます。",
            icon = Icons.Outlined.Info
        )
    }
    item {
        SettingsItem(
            title = "バックアップを書き出す",
            icon = Icons.Outlined.Upload,
            summary = "ブックマーク・タグ・会話・ノートなどを JSON ファイルに保存します(画像・PDF などのファイルは含めません)",
            onClick = actions.onExportBackup
        )
    }
    item {
        SettingsItem(
            title = "ファイルも含めて書き出す",
            icon = Icons.Outlined.FolderZip,
            summary = "保存した画像・PDF・音声・動画と、オフラインコピーも入れた zip ファイルに保存します。大きくなることがあります",
            onClick = actions.onExportBackupWithFiles
        )
    }
    item {
        SettingsItem(
            title = "バックアップを読み込む",
            icon = Icons.Outlined.Download,
            summary = "今のデータに追加します(JSON・zip)。同じ URL のブックマークはスキップします。壊れたファイルは何も読み込みません",
            onClick = actions.onImportBackup
        )
    }
    autoBackupItems(
        settings = uiState.autoBackup,
        onToggle = actions.onAutoBackupToggle,
        onPickFolder = actions.onPickAutoBackupFolder,
        onOpenKeepDialog = { openDialog(SettingsDialog.AUTO_BACKUP_KEEP) },
        onIncludeFilesChange = actions.onAutoBackupIncludeFilesChange,
        onRunNow = actions.onRunAutoBackupNow
    )
    item {
        SettingsItem(
            title = "他のサービスから取り込む",
            icon = Icons.Outlined.MoveToInbox,
            summary = "Pocket・Raindrop.io・Instapaper・Pinboard・ブラウザの書き出しファイル(HTML・CSV・JSON)から、リンクを追加します",
            onClick = actions.onImportFromServices
        )
    }
    item {
        SettingsItem(
            title = "購読(RSS・論文・ニュースなど)",
            icon = Icons.Outlined.RssFeed,
            summary = "ブログやチャンネルの新着、キーワード・タグに当てはまる論文・ニュース・技術記事、GitHub のリリースを、毎晩確認して自動で保存します",
            onClick = actions.onOpenSubscriptions
        )
    }
    item("offline-videos") {
        SettingsItem(
            title = "オフラインの動画",
            icon = Icons.Outlined.VideoLibrary,
            summary = "保存した動画を見る・削除する。Wi-Fi のときだけ・保存する上限を決める",
            onClick = actions.onOpenOfflineVideos
        )
    }
    item("storage") {
        SettingsItem(
            title = "ストレージ使用量",
            icon = Icons.Outlined.Storage,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            summary = uiState.storage?.label ?: "計算中…"
        )
    }

    item("about-heading") { SectionHeader("このアプリについて") }
    actions.onOpenOnboarding?.let { onOpenOnboarding ->
        item("guide") {
            SettingsItem(
                title = "はじめにの案内をもう一度見る",
                icon = Icons.Outlined.School,
                summary = "保存の仕方や AI の準備を確認できます",
                onClick = onOpenOnboarding,
                trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }
            )
        }
    }
    item("privacy") {
        SettingsItem(
            title = "プライバシー",
            icon = Icons.Outlined.PrivacyTip,
            summary = "データの保存場所と送信先",
            onClick = { openDialog(SettingsDialog.PRIVACY) }
        )
    }
    item("licenses") {
        SettingsItem(
            title = "オープンソースライセンス",
            icon = Icons.Outlined.Description,
            onClick = { openDialog(SettingsDialog.LICENSES) }
        )
    }
    item("crash-log") {
        SettingsItem(
            title = "クラッシュログ",
            icon = Icons.Outlined.BugReport,
            summary = if (uiState.crashLog.count == 0) "記録はありません" else "${uiState.crashLog.count} 件を端末内に保存しています",
            onClick = { openDialog(SettingsDialog.CRASH_LOG) }
        )
    }
    item("version") {
        SettingsItem(title = "バージョン", icon = Icons.Outlined.Info, summary = uiState.versionLabel)
    }

    item("deletion-heading") { SectionHeader("データの削除") }
    item("delete-data") {
        SettingsItem(
            title = "すべてのデータを削除",
            icon = Icons.Outlined.DeleteForever,
            summary = "ブックマーク・タグ・会話・ファイルを削除します",
            titleColor = MaterialTheme.colorScheme.error,
            iconTint = MaterialTheme.colorScheme.error,
            onClick = { openDialog(SettingsDialog.DELETE_CONFIRM) }
        )
    }
}

/** 保存したデータの暗号化の状態(#100)。うまくいっていないときは、理由とすることを出す */
private fun LazyListScope.databaseEncryptionItems(
    state: DatabaseEncryptionState,
    onDismissKeyLost: () -> Unit
) {
    item {
        SettingsItem(
            title = "保存したデータの暗号化",
            icon = Icons.Outlined.EnhancedEncryption,
            summary = when (state) {
                DatabaseEncryptionState.PLAINTEXT_FALLBACK -> "暗号化できなかったため、今は暗号化せずに保存しています。次にアプリを開いたときにやり直します"
                else -> DATABASE_ENCRYPTION_SUMMARY
            }
        )
    }
    if (state == DatabaseEncryptionState.KEY_LOST) {
        item {
            SettingsNotice(
                text = "暗号化の鍵がこの端末で使えなくなったため、保存していたデータを読めず、新しく作り直しました。" +
                    "バックアップがあれば、「データ管理」→「バックアップを読み込む」で戻してください。",
                icon = Icons.Outlined.WarningAmber,
                isWarning = true,
                action = {
                    TextButton(
                        onClick = onDismissKeyLost,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { Text("閉じる") }
                }
            )
        }
    }
}

/** 暗号化の説明。何を守り、何は端末の暗号化に任せているかを書く */
internal const val DATABASE_ENCRYPTION_SUMMARY =
    "ブックマーク・タグ・会話・ノートなどのデータベースを、この端末だけの鍵で暗号化して保存しています。" +
        "画像・PDF などのファイルと、検索用のデータは、端末の暗号化で守られます。"

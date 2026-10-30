package com.unchunks.echomark.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.unchunks.components.composeui.MessageSnackbarEffect
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.model.SummaryStyle
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.ui.common.formatFileSize
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale


/** 設定画面で開くダイアログ。スクリーンショットテストでは最初から開いた状態を渡せる。 */
enum class SettingsDialog {
    THEME, DAY_OF_WEEK, TIME, DELETE_CONFIRM, DELETE_FINAL, PRIVACY, LICENSES, CRASH_LOG,
    NIGHTLY_HOUR, CONCURRENCY, API_RATE, SUMMARY_LANGUAGE, SUMMARY_FORMAT
}

/** 設定画面の操作。既定は何もしない(プレビュー・スクリーンショット用)。 */
class SettingsActions(
    val onOpenAiSettings: () -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
    /** 処理の設定(充電中だけ・夜間にまとめて・同時に処理する数など)を書き換える */
    val onProcessingSettingsChange: ((ProcessingSettings) -> ProcessingSettings) -> Unit = {},
    /** 要約の言語・書き方を書き換える */
    val onSummaryStyleChange: ((SummaryStyle) -> SummaryStyle) -> Unit = {},
    /** 処理の状況の画面を開く */
    val onOpenProcessingQueue: () -> Unit = {},
    /** 再発見通知のスイッチ。オンにするときの権限要求は呼び出し側で行う */
    val onRediscoverToggle: (Boolean) -> Unit = {},
    val onRediscoverScheduleChange: (DayOfWeek, Int, Int) -> Unit = { _, _, _ -> },
    val onOpenNotificationSettings: () -> Unit = {},
    val onExportBackup: () -> Unit = {},
    val onImportBackup: () -> Unit = {},
    val onDeleteAllData: (resetSettings: Boolean) -> Unit = {},
    val onDataResultDismissed: () -> Unit = {},
    val onShareCrashLog: () -> Unit = {},
    val onClearCrashLog: () -> Unit = {},
    /** null なら「はじめにの案内」の行を出さない */
    val onOpenOnboarding: (() -> Unit)? = null
)

/** 設定タブ。状態は [SettingsViewModel]、表示は [SettingsContent]。 */
@Composable
fun SettingsScreen(
    onOpenAiSettings: () -> Unit,
    onOpenOnboarding: (() -> Unit)? = null,
    onOpenProcessingQueue: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    MessageSnackbarEffect(viewModel.message, snackbarHostState, onShown = viewModel::onMessageShown)

    // 端末の設定で通知が許可されているか。設定アプリから戻ったときにも確認し直す
    var notificationsAllowed by remember { mutableStateOf(true) }
    var permissionDenied by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (notificationsAllowed) permissionDenied = false
        // 端末内モデルの取り込み・削除でサイズが変わるため
        viewModel.refreshStorage()
        viewModel.refreshCrashLog()
        onPauseOrDispose {}
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionDenied = false
            notificationsAllowed = true
            viewModel.setRediscoverEnabled(true)
        } else {
            permissionDenied = true
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.exportBackup(uri.toString())
    }
    // .json の MIME タイプはファイルアプリによって異なるため、よく使われるものをまとめて許可し、中身で判定する
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBackup(uri.toString())
    }

    SettingsContent(
        uiState = uiState,
        notificationsBlocked = permissionDenied || (uiState.rediscover.enabled && !notificationsAllowed),
        snackbarHostState = snackbarHostState,
        actions = SettingsActions(
            onOpenAiSettings = onOpenAiSettings,
            onThemeModeChange = viewModel::setThemeMode,
            onDynamicColorChange = viewModel::setDynamicColor,
            onProcessingSettingsChange = viewModel::updateProcessingSettings,
            onSummaryStyleChange = viewModel::updateSummaryStyle,
            onOpenProcessingQueue = onOpenProcessingQueue,
            onRediscoverToggle = { enabled ->
                when {
                    !enabled -> {
                        permissionDenied = false
                        viewModel.setRediscoverEnabled(false)
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED ->
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> viewModel.setRediscoverEnabled(true)
                }
            },
            onRediscoverScheduleChange = viewModel::setRediscoverSchedule,
            onOpenNotificationSettings = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                    )
                }
            },
            onExportBackup = { exportLauncher.launch("echomark-backup-${LocalDate.now()}.json") },
            onImportBackup = { importLauncher.launch(arrayOf(BACKUP_MIME_TYPE, "text/plain", "application/octet-stream")) },
            onDeleteAllData = viewModel::deleteAllData,
            onDataResultDismissed = viewModel::onDataResultDismissed,
            onShareCrashLog = {
                scope.launch {
                    val text = viewModel.crashLogText() ?: return@launch
                    shareCrashLog(context, text)
                }
            },
            onClearCrashLog = viewModel::clearCrashLog,
            onOpenOnboarding = onOpenOnboarding
        )
    )
}

/**
 * 設定画面の中身。状態とコールバックを受け取るだけなので、スクリーンショットテストで描画できる。
 * @param notificationsBlocked 再発見通知を出せない(権限が拒否・端末の設定でオフ)
 * @param dynamicColorAvailable ダイナミックカラーを選べるか(Android 12+。minSdk 31 のため実機では常に true)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    notificationsBlocked: Boolean = false,
    dynamicColorAvailable: Boolean = true,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initialDialog: SettingsDialog? = null
) {
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    var resetSettingsOnDelete by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(title = { Text("設定") }, scrollBehavior = scrollBehavior)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (!uiState.isLoaded) {
            LoadingState(modifier = Modifier.padding(innerPadding))
            return@Scaffold
        }
        val layoutDirection = LocalLayoutDirection.current
        ReadableLazyColumn(
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp
            )
        ) {
            item { SectionHeader("AI") }
            item {
                val ai = uiState.ai
                SettingsItem(
                    title = "AI の設定",
                    icon = Icons.Outlined.AutoAwesome,
                    summary = ai.label,
                    onClick = actions.onOpenAiSettings,
                    trailing = {
                        Icon(
                            if (ai.isReady) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                            contentDescription = if (ai.isReady) "準備完了" else "設定が必要",
                            tint = if (ai.isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                )
            }

            processingSettingsItems(
                settings = uiState.processing,
                onChange = actions.onProcessingSettingsChange,
                onOpenDialog = { dialog = it },
                onOpenQueue = actions.onOpenProcessingQueue
            )

            // クラウドの AI を無効にしている間は、使用量の欄も出さない(CLOUD_AI_ENABLED。記録も無い)
            if (CLOUD_AI_ENABLED) apiUsageItems(uiState.apiUsage)

            summarySettingsItems(uiState.summaryStyle, onOpenDialog = { dialog = it })

            item { SectionHeader("表示") }
            item {
                SettingsItem(
                    title = "テーマ",
                    icon = Icons.Outlined.BrightnessMedium,
                    summary = uiState.themeMode.label,
                    onClick = { dialog = SettingsDialog.THEME }
                )
            }
            if (dynamicColorAvailable) {
                item {
                    SettingsSwitchItem(
                        title = "ダイナミックカラー",
                        icon = Icons.Outlined.Palette,
                        summary = "壁紙の色をアプリの配色に使います",
                        checked = uiState.dynamicColor,
                        onCheckedChange = actions.onDynamicColorChange
                    )
                }
            }

            item { SectionHeader("通知") }
            item {
                SettingsSwitchItem(
                    title = "再発見通知",
                    icon = Icons.Outlined.NotificationsActive,
                    summary = "週に1回、忘れかけたブックマークをお知らせ",
                    checked = uiState.rediscover.enabled,
                    onCheckedChange = actions.onRediscoverToggle
                )
            }
            if (notificationsBlocked) {
                item {
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
                item {
                    SettingsItem(
                        title = "曜日",
                        icon = Icons.Outlined.CalendarMonth,
                        summary = uiState.rediscover.dayOfWeek.fullLabel,
                        onClick = { dialog = SettingsDialog.DAY_OF_WEEK }
                    )
                }
                item {
                    SettingsItem(
                        title = "時刻",
                        icon = Icons.Outlined.Schedule,
                        summary = "%02d:%02d".format(uiState.rediscover.hour, uiState.rediscover.minute),
                        onClick = { dialog = SettingsDialog.TIME }
                    )
                }
            }

            item { SectionHeader("データ") }
            item {
                SettingsItem(
                    title = "バックアップを書き出す",
                    icon = Icons.Outlined.Upload,
                    summary = "ブックマーク・タグ・会話を JSON ファイルに保存します",
                    onClick = actions.onExportBackup
                )
            }
            item {
                SettingsItem(
                    title = "バックアップを読み込む",
                    icon = Icons.Outlined.Download,
                    summary = "今のデータに追加します。同じ URL のブックマークはスキップします",
                    onClick = actions.onImportBackup
                )
            }
            item {
                SettingsNotice(
                    text = "API キーと端末内モデル、保存した画像・PDF・音声などのファイル本体はバックアップに含まれません。別の端末では設定し直してください(ファイルのブックマークは、要約・メモだけが読み込まれます)。",
                    icon = Icons.Outlined.Info
                )
            }
            item {
                SettingsItem(
                    title = "ストレージ使用量",
                    icon = Icons.Outlined.Storage,
                    summary = uiState.storage?.label ?: "計算中…"
                )
            }
            item {
                SettingsItem(
                    title = "すべてのデータを削除",
                    icon = Icons.Outlined.DeleteForever,
                    summary = "ブックマーク・タグ・会話・ファイルを削除します",
                    titleColor = MaterialTheme.colorScheme.error,
                    iconTint = MaterialTheme.colorScheme.error,
                    onClick = {
                        resetSettingsOnDelete = false
                        dialog = SettingsDialog.DELETE_CONFIRM
                    }
                )
            }

            item { SectionHeader("このアプリについて") }
            actions.onOpenOnboarding?.let { onOpenOnboarding ->
                item {
                    SettingsItem(
                        title = "はじめにの案内をもう一度見る",
                        icon = Icons.Outlined.School,
                        summary = "保存の仕方や AI の準備を確認できます",
                        onClick = onOpenOnboarding,
                        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }
                    )
                }
            }
            item {
                SettingsItem(
                    title = "プライバシー",
                    icon = Icons.Outlined.PrivacyTip,
                    summary = "データの保存場所と送信先",
                    onClick = { dialog = SettingsDialog.PRIVACY }
                )
            }
            item {
                SettingsItem(
                    title = "オープンソースライセンス",
                    icon = Icons.Outlined.Description,
                    onClick = { dialog = SettingsDialog.LICENSES }
                )
            }
            item {
                SettingsItem(
                    title = "クラッシュログ",
                    icon = Icons.Outlined.BugReport,
                    summary = if (uiState.crashLog.count == 0) "記録はありません" else "${uiState.crashLog.count} 件を端末内に保存しています",
                    onClick = { dialog = SettingsDialog.CRASH_LOG }
                )
            }
            item {
                SettingsItem(title = "バージョン", icon = Icons.Outlined.Info, summary = uiState.versionLabel)
            }
        }
    }

    when (dialog) {
        SettingsDialog.THEME -> ThemeModeDialog(
            selected = uiState.themeMode,
            onSelect = {
                actions.onThemeModeChange(it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.DAY_OF_WEEK -> DayOfWeekDialog(
            selected = uiState.rediscover.dayOfWeek,
            onSelect = {
                actions.onRediscoverScheduleChange(it, uiState.rediscover.hour, uiState.rediscover.minute)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.TIME -> TimeDialog(
            hour = uiState.rediscover.hour,
            minute = uiState.rediscover.minute,
            onConfirm = { hour, minute ->
                actions.onRediscoverScheduleChange(uiState.rediscover.dayOfWeek, hour, minute)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.DELETE_CONFIRM -> DeleteConfirmDialog(
            resetSettings = resetSettingsOnDelete,
            onResetSettingsChange = { resetSettingsOnDelete = it },
            onNext = { dialog = SettingsDialog.DELETE_FINAL },
            onDismiss = { dialog = null }
        )
        SettingsDialog.DELETE_FINAL -> DeleteFinalDialog(
            resetSettings = resetSettingsOnDelete,
            onConfirm = {
                actions.onDeleteAllData(resetSettingsOnDelete)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.PRIVACY -> PrivacyDialog(backends = uiState.ai.backends, onDismiss = { dialog = null })
        SettingsDialog.LICENSES -> LicensesDialog(onDismiss = { dialog = null })
        SettingsDialog.CRASH_LOG -> CrashLogDialog(
            crashLog = uiState.crashLog,
            onShare = actions.onShareCrashLog,
            onClear = actions.onClearCrashLog,
            onDismiss = { dialog = null }
        )
        SettingsDialog.NIGHTLY_HOUR, SettingsDialog.CONCURRENCY, SettingsDialog.API_RATE -> ProcessingSettingsDialog(
            dialog = dialog!!,
            settings = uiState.processing,
            onChange = actions.onProcessingSettingsChange,
            onDismiss = { dialog = null }
        )
        SettingsDialog.SUMMARY_LANGUAGE, SettingsDialog.SUMMARY_FORMAT -> SummarySettingsDialog(
            dialog = dialog!!,
            style = uiState.summaryStyle,
            onChange = actions.onSummaryStyleChange,
            onDismiss = { dialog = null }
        )
        null -> Unit
    }

    DataOperationDialog(state = uiState.dataOperation, onDismiss = actions.onDataResultDismissed)
}

// ---- 表示用の文言 ----

internal val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "端末の設定に合わせる"
        ThemeMode.LIGHT -> "ライト"
        ThemeMode.DARK -> "ダーク"
    }

internal val DayOfWeek.fullLabel: String get() = getDisplayName(TextStyle.FULL, Locale.JAPANESE)

private val StorageUsage.label: String
    get() = buildList {
        add("データ ${formatFileSize(databaseBytes)}")
        add("検索用 ${formatFileSize(embeddingBytes)}")
        if (attachmentBytes > 0) add("ファイル ${formatFileSize(attachmentBytes)}")
        if (modelBytes > 0) add("モデル ${formatFileSize(modelBytes)}")
    }.joinToString("・")

private const val BACKUP_MIME_TYPE = "application/json"

/** クラッシュログを1つのテキストファイルにして、共有先を選ばせる(FileProvider で一時的な読み取り権限つきで渡す)。 */
private suspend fun shareCrashLog(context: Context, text: String) {
    val uri = withContext(Dispatchers.IO) {
        val file = File(File(context.cacheDir, "shared-logs").apply { mkdirs() }, "echomark-crash-log-${LocalDate.now()}.txt")
        file.writeText(text, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "EchoMark クラッシュログ")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, "クラッシュログを共有").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "クラッシュログの共有先がない")
    }
}

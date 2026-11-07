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
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VisibilityOff
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
import com.unchunks.echomark.ui.importer.LinkImportDialog
import com.unchunks.echomark.ui.importer.LinkImportViewModel
import com.unchunks.echomark.ui.subscription.SubscriptionsSheet
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
import com.unchunks.echomark.ui.lock.rememberAppLockConfirmation
import com.unchunks.echomark.domain.lock.AppLockDelay
import com.unchunks.echomark.domain.backup.AutoBackupSettings
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
    NIGHTLY_HOUR, CONCURRENCY, API_RATE, SUMMARY_LANGUAGE, SUMMARY_FORMAT, APP_LOCK_DELAY, AUTO_BACKUP_KEEP
}

/** 設定画面の操作。既定は何もしない(プレビュー・スクリーンショット用)。 */
class SettingsActions(
    val onOpenAiSettings: () -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
    /** オフラインモードのスイッチ */
    val onOfflineModeChange: (Boolean) -> Unit = {},
    /** アプリのロックのスイッチ。オンにするときの本人の確認は呼び出し側で行う */
    val onAppLockChange: (Boolean) -> Unit = {},
    val onAppLockDelayChange: (AppLockDelay) -> Unit = {},
    /** 最近使ったアプリの一覧に内容を出さない(FLAG_SECURE) */
    val onAppLockHideContentChange: (Boolean) -> Unit = {},
    /** 処理の設定(充電中だけ・夜間にまとめて・同時に処理する数など)を書き換える */
    val onProcessingSettingsChange: ((ProcessingSettings) -> ProcessingSettings) -> Unit = {},
    /** 要約の言語・書き方を書き換える */
    val onSummaryStyleChange: ((SummaryStyle) -> SummaryStyle) -> Unit = {},
    /** 処理の状況の画面を開く */
    val onOpenProcessingQueue: () -> Unit = {},
    /** カテゴリの管理の画面を開く */
    val onOpenCategories: () -> Unit = {},
    /** 再発見通知のスイッチ。オンにするときの権限要求は呼び出し側で行う */
    val onRediscoverToggle: (Boolean) -> Unit = {},
    val onRediscoverScheduleChange: (DayOfWeek, Int, Int) -> Unit = { _, _, _ -> },
    val onOpenNotificationSettings: () -> Unit = {},
    val onExportBackup: () -> Unit = {},
    /** 添付ファイル・オフラインコピーも入れて(zip で)書き出す */
    val onExportBackupWithFiles: () -> Unit = {},
    val onImportBackup: () -> Unit = {},
    /** 他のサービス(Pocket・Raindrop.io・ブラウザなど)の書き出しファイルを取り込む(ファイルを選ぶ) */
    val onImportFromServices: () -> Unit = {},
    /** 購読するフィード(RSS・YouTube のチャンネル)の管理を開く */
    val onOpenSubscriptions: () -> Unit = {},
    val onDeleteAllData: (resetSettings: Boolean) -> Unit = {},
    val onDataResultDismissed: () -> Unit = {},
    /** 読み込んだバックアップの設定も戻す */
    val onRestoreBackupSettings: () -> Unit = {},
    /** 自動バックアップのスイッチ(保存先を選んでいなければ、選ぶ画面を開く) */
    val onAutoBackupToggle: (Boolean) -> Unit = {},
    /** 自動バックアップの保存先のフォルダを選ぶ */
    val onPickAutoBackupFolder: () -> Unit = {},
    val onAutoBackupKeepChange: (Int) -> Unit = {},
    val onAutoBackupIncludeFilesChange: (Boolean) -> Unit = {},
    val onRunAutoBackupNow: () -> Unit = {},
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
    onOpenCategories: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    importViewModel: LinkImportViewModel = hiltViewModel()
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

    // ロックをオンにする前に、この端末で本人を確かめられるかを試す(締め出されないように)
    val confirmAppLock = rememberAppLockConfirmation(
        onConfirmed = { viewModel.setAppLockEnabled(true) },
        onUnavailable = viewModel::onAppLockUnavailable
    )
    // 自動バックアップの保存先。選んだフォルダの読み書きの許可を持ち続ける(前のフォルダの許可は手放す)
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (takeFolderPermission(context, uri)) {
            val previous = uiState.autoBackup.folderUri
            if (previous != null && previous != uri.toString()) releaseFolderPermission(context, previous)
            viewModel.setAutoBackupFolder(uri.toString(), folderLabelOf(context, uri))
        } else {
            viewModel.onAutoBackupFolderUnavailable()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.exportBackup(uri.toString())
    }
    // 添付ファイル・オフラインコピーも入れた zip
    val archiveExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_ARCHIVE_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.exportBackup(uri.toString(), includeFiles = true)
    }
    // .json の MIME タイプはファイルアプリによって異なるため、よく使われるものをまとめて許可し、中身で判定する
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBackup(uri.toString())
    }

    // 書き出しファイルの形式(HTML・CSV・JSON)はファイルアプリによって MIME タイプが異なるため、すべて選べるようにして中身で判定する
    val linkImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importViewModel.onFilePicked(uri.toString())
    }
    val importState by importViewModel.state.collectAsState()
    var showSubscriptions by rememberSaveable { mutableStateOf(false) }
    if (showSubscriptions) SubscriptionsSheet(onDismiss = { showSubscriptions = false })
    LinkImportDialog(
        state = importState,
        onFolderAsTagChange = importViewModel::setFolderAsTag,
        onImport = importViewModel::startImport,
        onClose = importViewModel::dismiss
    )

    SettingsContent(
        uiState = uiState,
        notificationsBlocked = permissionDenied || (uiState.rediscover.enabled && !notificationsAllowed),
        snackbarHostState = snackbarHostState,
        actions = SettingsActions(
            onOpenAiSettings = onOpenAiSettings,
            onThemeModeChange = viewModel::setThemeMode,
            onDynamicColorChange = viewModel::setDynamicColor,
            onOfflineModeChange = viewModel::setOfflineMode,
            onAppLockChange = { enabled -> if (enabled) confirmAppLock() else viewModel.setAppLockEnabled(false) },
            onAppLockDelayChange = viewModel::setAppLockDelay,
            onAppLockHideContentChange = viewModel::setAppLockHideContent,
            onProcessingSettingsChange = viewModel::updateProcessingSettings,
            onSummaryStyleChange = viewModel::updateSummaryStyle,
            onOpenProcessingQueue = onOpenProcessingQueue,
            onOpenCategories = onOpenCategories,
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
            onExportBackupWithFiles = { archiveExportLauncher.launch("echomark-backup-${LocalDate.now()}.zip") },
            onImportBackup = { importLauncher.launch(arrayOf(BACKUP_MIME_TYPE, BACKUP_ARCHIVE_MIME_TYPE, "text/plain", "application/octet-stream")) },
            onImportFromServices = { linkImportLauncher.launch(arrayOf("*/*")) },
            onOpenSubscriptions = { showSubscriptions = true },
            onDeleteAllData = viewModel::deleteAllData,
            onDataResultDismissed = viewModel::onDataResultDismissed,
            onRestoreBackupSettings = viewModel::restoreBackupSettings,
            onAutoBackupToggle = { enabled ->
                val backup = uiState.autoBackup
                when {
                    !enabled -> viewModel.setAutoBackupEnabled(false)
                    // 保存先を選んでいない、または許可がなくなっていれば、選んでもらう
                    backup.folderUri == null || !hasFolderPermission(context, backup.folderUri) -> folderLauncher.launch(null)
                    else -> viewModel.setAutoBackupEnabled(true)
                }
            },
            onPickAutoBackupFolder = { folderLauncher.launch(null) },
            onAutoBackupKeepChange = viewModel::setAutoBackupKeep,
            onAutoBackupIncludeFilesChange = viewModel::setAutoBackupIncludeFiles,
            onRunAutoBackupNow = viewModel::runAutoBackupNow,
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

            item {
                SettingsItem(
                    title = "カテゴリ",
                    icon = Icons.Outlined.Category,
                    summary = "AI の分類に使うカテゴリを作る・説明を見る",
                    onClick = actions.onOpenCategories
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

            summarySettingsItems(
                uiState.summaryStyle,
                onOpenDialog = { dialog = it },
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
                        onClick = { dialog = SettingsDialog.APP_LOCK_DELAY }
                    )
                }
            }
            item {
                SettingsSwitchItem(
                    title = "最近使ったアプリで内容を隠す",
                    icon = Icons.Outlined.VisibilityOff,
                    summary = "アプリの一覧に画面の内容を出しません。この設定の間は、スクリーンショットも撮れません",
                    checked = uiState.appLock.hideContent,
                    onCheckedChange = actions.onAppLockHideContentChange
                )
            }

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
                    summary = "週に1回、今週のふり返りと忘れかけたブックマークをお知らせ",
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
                onOpenKeepDialog = { dialog = SettingsDialog.AUTO_BACKUP_KEEP },
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
                    title = "購読(RSS・YouTube)",
                    icon = Icons.Outlined.RssFeed,
                    summary = "ブログやチャンネルの新着を、毎晩確認して自動で保存します",
                    onClick = actions.onOpenSubscriptions
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
        SettingsDialog.APP_LOCK_DELAY -> ChoiceDialog(
            title = "ロックするまでの時間",
            options = AppLockDelay.entries,
            selected = uiState.appLock.delay,
            label = { "アプリを離れてから${it.label}" },
            onSelect = { delay ->
                actions.onAppLockDelayChange(delay)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.AUTO_BACKUP_KEEP -> ChoiceDialog(
            title = "残すバックアップの数",
            options = AutoBackupSettings.KEEP_CHOICES,
            selected = uiState.autoBackup.keep,
            label = { "新しい ${it} 個" },
            onSelect = { keep ->
                actions.onAutoBackupKeepChange(keep)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        null -> Unit
    }

    DataOperationDialog(
        state = uiState.dataOperation,
        onDismiss = actions.onDataResultDismissed,
        onRestoreSettings = actions.onRestoreBackupSettings
    )
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
private const val BACKUP_ARCHIVE_MIME_TYPE = "application/zip"

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

/** アプリのロックのスイッチの説明 */
internal const val APP_LOCK_SUMMARY =
    "開くときに、指紋・顔、または端末の画面ロック(PIN など)で本人を確かめます。" +
        "共有・引用・クイック設定のタイル・ウィジェット・通知から開くときも確かめ、ロック中はウィジェットに内容を出しません。"

/** オフラインモードのスイッチの説明。止まるものと、そのまま使えるものを並べる */
internal const val OFFLINE_MODE_SUMMARY =
    "オンの間、アプリは一切通信しません。保存・検索・端末内 AI・オフラインコピーはそのまま使えます。" +
        "リンクの本文の取得・フィードとリンク切れの確認・モデルのダウンロード・地図の背景は止まり、" +
        "本文の取得などはオフにしたときに始まります。"

/** [folderUri] の読み書きの許可を持ち続けているか */
private fun hasFolderPermission(context: Context, folderUri: String): Boolean =
    context.contentResolver.persistedUriPermissions.any { it.uri.toString() == folderUri && it.isWritePermission }

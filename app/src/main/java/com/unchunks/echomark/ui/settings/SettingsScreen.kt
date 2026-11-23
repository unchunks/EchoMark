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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.ui.importer.LinkImportDialog
import com.unchunks.echomark.ui.importer.LinkImportViewModel
import com.unchunks.echomark.ui.subscription.SubscriptionsSheet
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.window.core.layout.WindowSizeClass
import com.unchunks.components.composeui.MessageSnackbarEffect
import com.unchunks.echomark.domain.model.SummaryStyle
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.ui.common.formatFileSize
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.lock.rememberAppLockConfirmation
import com.unchunks.echomark.domain.lock.AppLockDelay
import com.unchunks.echomark.domain.backup.AutoBackupSettings
import com.unchunks.echomark.ui.navigation.AdaptiveLayout
import com.unchunks.echomark.ui.navigation.ListDetailPanes
import com.unchunks.echomark.ui.navigation.NavigationLayout
import com.unchunks.echomark.ui.navigation.centeredMaxWidth
import com.unchunks.echomark.ui.navigation.currentAdaptiveLayout
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
    THEME, DESIGN, DAY_OF_WEEK, TIME, DELETE_CONFIRM, DELETE_FINAL, PRIVACY, LICENSES, CRASH_LOG,
    NIGHTLY_HOUR, CONCURRENCY, API_RATE, SUMMARY_LANGUAGE, SUMMARY_FORMAT, APP_LOCK_DELAY, AUTO_BACKUP_KEEP
}

/** 設定画面の操作。既定は何もしない(プレビュー・スクリーンショット用)。 */
class SettingsActions(
    val onOpenAiSettings: () -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
    val onDesignStyleChange: (DesignStyle) -> Unit = {},
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
    /** オフラインの動画(保存した動画・ダウンロードの設定)の画面を開く */
    val onOpenOfflineVideos: () -> Unit = {},
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
    /** 暗号化の鍵が失われた知らせを閉じる */
    val onDismissDatabaseKeyLost: () -> Unit = {},
    val onShareCrashLog: () -> Unit = {},
    val onClearCrashLog: () -> Unit = {},
    /** null なら「はじめにの案内」の行を出さない */
    val onOpenOnboarding: (() -> Unit)? = null
)

/** 設定タブ。状態は [SettingsViewModel]、表示は [SettingsContent]。 */
@Composable
internal fun SettingsScreen(
    onOpenAiSettings: () -> Unit,
    onOpenOnboarding: (() -> Unit)? = null,
    onOpenProcessingQueue: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
    onOpenOfflineVideos: () -> Unit = {},
    layout: AdaptiveLayout = currentAdaptiveLayout(),
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
        layout = layout,
        actions = SettingsActions(
            onOpenAiSettings = onOpenAiSettings,
            onThemeModeChange = viewModel::setThemeMode,
            onDynamicColorChange = viewModel::setDynamicColor,
            onDesignStyleChange = viewModel::setDesignStyle,
            onOfflineModeChange = viewModel::setOfflineMode,
            onAppLockChange = { enabled -> if (enabled) confirmAppLock() else viewModel.setAppLockEnabled(false) },
            onAppLockDelayChange = viewModel::setAppLockDelay,
            onAppLockHideContentChange = viewModel::setAppLockHideContent,
            onProcessingSettingsChange = viewModel::updateProcessingSettings,
            onSummaryStyleChange = viewModel::updateSummaryStyle,
            onOpenProcessingQueue = onOpenProcessingQueue,
            onOpenCategories = onOpenCategories,
            onOpenOfflineVideos = onOpenOfflineVideos,
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
            onDismissDatabaseKeyLost = viewModel::dismissDatabaseKeyLost,
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
internal fun SettingsContent(
    uiState: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    notificationsBlocked: Boolean = false,
    dynamicColorAvailable: Boolean = true,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initialDialog: SettingsDialog? = null,
    layout: AdaptiveLayout = currentAdaptiveLayout()
) {
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    var resetSettingsOnDelete by rememberSaveable { mutableStateOf(false) }
    val compact = layout.navigation == NavigationLayout.BAR
    val useLargeBar = compact &&
        LocalConfiguration.current.screenHeightDp >= WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val singleColumnState = rememberLazyListState()
    val preferencesState = rememberLazyListState()
    val managementState = rememberLazyListState()

    Scaffold(
        modifier = if (useLargeBar) modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else modifier,
        topBar = {
            val title: @Composable () -> Unit = { Text("設定", Modifier.semantics { heading() }) }
            if (useLargeBar) {
                LargeTopAppBar(title = title, scrollBehavior = scrollBehavior)
            } else {
                TopAppBar(title = title)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (!uiState.isLoaded) {
            LoadingState(modifier = Modifier.padding(innerPadding), message = "設定を読み込んでいます")
            return@Scaffold
        }
        val openDialog: (SettingsDialog) -> Unit = {
            if (it == SettingsDialog.DELETE_CONFIRM) resetSettingsOnDelete = false
            dialog = it
        }
        val contentModifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
        if (layout.isTwoPane) {
            ListDetailPanes(
                directive = layout.paneDirective,
                modifier = contentModifier.fillMaxSize(),
                list = {
                    SettingsColumn(state = preferencesState, gutter = 24.dp) {
                        preferenceItems(uiState, actions, notificationsBlocked, dynamicColorAvailable, openDialog)
                    }
                },
                detail = {
                    SettingsColumn(state = managementState, gutter = 24.dp) {
                        managementItems(uiState, actions, openDialog)
                    }
                }
            )
        } else {
            SettingsColumn(
                state = singleColumnState,
                gutter = if (compact) 16.dp else 24.dp,
                modifier = contentModifier
            ) {
                preferenceItems(uiState, actions, notificationsBlocked, dynamicColorAvailable, openDialog)
                managementItems(uiState, actions, openDialog)
            }
        }
    }
    if (uiState.dataOperation != DataOperationState.Idle) {
        DataOperationDialog(
            state = uiState.dataOperation,
            onDismiss = actions.onDataResultDismissed,
            onRestoreSettings = actions.onRestoreBackupSettings
        )
        return
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
        SettingsDialog.DESIGN -> DesignStyleDialog(
            selected = uiState.designStyle,
            dynamicColor = uiState.dynamicColor,
            onSelect = {
                actions.onDesignStyleChange(it)
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
}

@Composable
private fun SettingsColumn(
    state: LazyListState,
    gutter: Dp,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        state = state,
        modifier = modifier
            .centeredMaxWidth(600.dp)
            .semantics { isTraversalGroup = true },
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 24.dp),
        content = content
    )
}

// ---- 表示用の文言 ----

internal val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "端末の設定に合わせる"
        ThemeMode.LIGHT -> "ライト"
        ThemeMode.DARK -> "ダーク"
    }

internal val DesignStyle.label: String
    get() = when (this) {
        DesignStyle.CLASSIC -> "クラシック"
        DesignStyle.ECHO -> "エコー"
        DesignStyle.PAPER -> "ペーパー"
    }

internal val DesignStyle.description: String
    get() = when (this) {
        DesignStyle.CLASSIC -> "これまでの標準的な見た目"
        DesignStyle.ECHO -> "大きな見出しと丸みのある形で、再発見を目立たせます"
        DesignStyle.PAPER -> "明朝体の見出しと紙色の背景で、読むことに集中できます"
    }

internal val DayOfWeek.fullLabel: String get() = getDisplayName(TextStyle.FULL, Locale.JAPANESE)

internal val StorageUsage.label: String
    get() = buildList {
        add("データ ${formatFileSize(databaseBytes)}")
        add("検索用 ${formatFileSize(embeddingBytes)}")
        if (attachmentBytes > 0) add("ファイル ${formatFileSize(attachmentBytes)}")
        if (offlineVideoBytes > 0) add("動画 ${formatFileSize(offlineVideoBytes)}")
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

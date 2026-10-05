package com.unchunks.echomark.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.unchunks.echomark.domain.repository.BackupImportSummary
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.ui.common.MessageSnackbarEffect
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.components.SectionHeader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** 設定画面で開くダイアログ。スクリーンショットテストでは最初から開いた状態を渡せる。 */
enum class SettingsDialog { THEME, DAY_OF_WEEK, TIME, DELETE_CONFIRM, DELETE_FINAL, PRIVACY, LICENSES }

/** 設定画面の操作。既定は何もしない(プレビュー・スクリーンショット用)。 */
class SettingsActions(
    val onOpenAiSettings: () -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
    /** 再発見通知のスイッチ。オンにするときの権限要求は呼び出し側で行う */
    val onRediscoverToggle: (Boolean) -> Unit = {},
    val onRediscoverScheduleChange: (DayOfWeek, Int, Int) -> Unit = { _, _, _ -> },
    val onOpenNotificationSettings: () -> Unit = {},
    val onExportBackup: () -> Unit = {},
    val onImportBackup: () -> Unit = {},
    val onDeleteAllData: (resetSettings: Boolean) -> Unit = {},
    val onDataResultDismissed: () -> Unit = {},
    /** null なら「はじめにの案内」の行を出さない */
    val onOpenOnboarding: (() -> Unit)? = null
)

/** 設定タブ。状態は [SettingsViewModel]、表示は [SettingsContent]。 */
@Composable
fun SettingsScreen(
    onOpenAiSettings: () -> Unit,
    onOpenOnboarding: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    MessageSnackbarEffect(viewModel.message, snackbarHostState, onShown = viewModel::onMessageShown)

    // 端末の設定で通知が許可されているか。設定アプリから戻ったときにも確認し直す
    var notificationsAllowed by remember { mutableStateOf(true) }
    var permissionDenied by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (notificationsAllowed) permissionDenied = false
        // 端末内モデルの取り込み・削除でサイズが変わるため
        viewModel.refreshStorage()
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
        LazyColumn(
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
        SettingsDialog.PRIVACY -> PrivacyDialog(backend = uiState.ai.backend, onDismiss = { dialog = null })
        SettingsDialog.LICENSES -> LicensesDialog(onDismiss = { dialog = null })
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

private val DayOfWeek.fullLabel: String get() = getDisplayName(TextStyle.FULL, Locale.JAPANESE)

private val StorageUsage.label: String
    get() = buildList {
        add("データ ${formatStorageSize(databaseBytes)}")
        add("検索用 ${formatStorageSize(embeddingBytes)}")
        if (attachmentBytes > 0) add("ファイル ${formatStorageSize(attachmentBytes)}")
        if (modelBytes > 0) add("モデル ${formatStorageSize(modelBytes)}")
    }.joinToString("・")

// ---- ダイアログ ----

/** ラジオボタンで1つ選ぶダイアログ。行全体をタップ対象にする。 */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = option == selected,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.size(16.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge.japaneseParagraph())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
internal fun ThemeModeDialog(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog(
        title = "テーマ",
        options = ThemeMode.entries,
        selected = selected,
        label = { it.label },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@Composable
private fun DayOfWeekDialog(selected: DayOfWeek, onSelect: (DayOfWeek) -> Unit, onDismiss: () -> Unit) {
    // 日曜始まりで並べる
    val days = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }
    ChoiceDialog(
        title = "通知する曜日",
        options = days,
        selected = selected,
        label = { it.fullLabel },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(hour: Int, minute: Int, onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通知する時刻") },
        text = { TimePicker(state = timeState) },
        confirmButton = { TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
internal fun DeleteConfirmDialog(
    resetSettings: Boolean,
    onResetSettingsChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
        title = { Text("すべてのデータを削除しますか?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "保存したブックマーク・タグ・会話・ファイルと、検索用のデータを削除します。必要なら先にバックアップを書き出してください。",
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = resetSettings, role = Role.Checkbox, onValueChange = onResetSettingsChange),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = resetSettings, onCheckedChange = null)
                    Spacer(Modifier.size(12.dp))
                    Text("設定と API キーも初期化する", style = MaterialTheme.typography.bodyLarge.japaneseParagraph())
                }
                Text(
                    "端末内モデルは残ります(AI の設定から削除できます)。",
                    style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onNext) { Text("次へ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
internal fun DeleteFinalDialog(resetSettings: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("本当に削除しますか?") },
        text = {
            Text(
                if (resetSettings) "データの削除と設定の初期化は取り消せません。" else "削除したデータは元に戻せません。",
                style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("削除する") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
internal fun PrivacyDialog(backend: LlmBackend, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
        title = { Text("プライバシー") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrivacyPoint(
                    "保存場所",
                    "ブックマーク・タグ・会話は、この端末の中だけに保存します。EchoMark のサーバーはありません。"
                )
                PrivacyPoint(
                    "端末内で動かす(既定)",
                    "要約・タグ付け・チャット・検索は端末内で処理し、内容を外部に送信しません。"
                )
                PrivacyPoint(
                    "クラウド API を選んだとき",
                    "ブックマークの本文・要約とチャットの質問を、選んだ提供元(Anthropic・Google・OpenAI)のサーバーに送信します。" +
                        "検索用のデータは引き続き端末内で作ります。"
                )
                PrivacyPoint(
                    "URL の保存",
                    "本文とプレビュー画像を取得するため、保存したページにアクセスします。"
                )
                PrivacyPoint(
                    "API キー",
                    "端末内で暗号化して保存し、バックアップには含めません。"
                )
                Text(
                    "いまの設定: " + if (backend == LlmBackend.LOCAL) "端末内で動かす" else "クラウド API を使う",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}

@Composable
private fun PrivacyPoint(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium.japaneseParagraph(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 使っている主なライブラリとライセンス。 */
private val OPEN_SOURCE_LIBRARIES = listOf(
    "Android Jetpack(Compose・Room・WorkManager・DataStore・Navigation ほか)" to "Apache License 2.0",
    "Kotlin・kotlinx.coroutines" to "Apache License 2.0",
    "Dagger / Hilt" to "Apache License 2.0",
    "Material Components / Material Icons" to "Apache License 2.0",
    "MediaPipe Tasks(テキスト埋め込み・LLM 推論)" to "Apache License 2.0",
    "ObjectBox Java" to "Apache License 2.0",
    "OkHttp" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "Timber" to "Apache License 2.0",
    "jsoup" to "MIT License",
    "Anthropic Java SDK" to "MIT License",
    "EmbeddingGemma(埋め込みモデル)" to "Gemma Terms of Use"
)

@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("オープンソースライセンス") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "EchoMark は次のオープンソースソフトウェアなどを利用しています。",
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
                HorizontalDivider()
                OPEN_SOURCE_LIBRARIES.forEach { (name, license) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyMedium.japaneseParagraph())
                        Text(
                            license,
                            style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}

/** バックアップ・削除の実行中表示と、読み込み結果・エラーのダイアログ。 */
@Composable
internal fun DataOperationDialog(state: DataOperationState, onDismiss: () -> Unit) {
    when (state) {
        DataOperationState.Idle -> Unit
        is DataOperationState.Running -> AlertDialog(
            // 処理中は閉じられないようにする(結果が出たら自動で切り替わる)
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.size(16.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyLarge.japaneseParagraph())
                }
            },
            confirmButton = {}
        )
        is DataOperationState.Imported -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
            title = { Text("読み込みました") },
            text = { ImportSummaryText(state.summary) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
        is DataOperationState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(state.title) },
            text = { Text(state.message, style = MaterialTheme.typography.bodyMedium.japaneseParagraph()) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
    }
}

@Composable
private fun ImportSummaryText(summary: BackupImportSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SummaryRow("追加したブックマーク", "${summary.bookmarksAdded} 件")
        if (summary.bookmarksSkipped > 0) SummaryRow("登録済みでスキップ", "${summary.bookmarksSkipped} 件")
        SummaryRow("追加したタグ", "${summary.tagsAdded} 件")
        SummaryRow("追加した会話", "${summary.conversationsAdded} 件")
        if (summary.conversationsSkipped > 0) SummaryRow("登録済みの会話", "${summary.conversationsSkipped} 件")
        if (summary.invalidRecords > 0) SummaryRow("壊れていて読み飛ばした項目", "${summary.invalidRecords} 件")
        if (summary.bookmarksAdded > 0) {
            Spacer(Modifier.size(4.dp))
            Text(
                "検索用のデータと AI の要約は、裏で順に作り直します。",
                style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium.japaneseParagraph(), modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.japaneseParagraph())
    }
}

private const val BACKUP_MIME_TYPE = "application/json"

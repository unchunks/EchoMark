package com.unchunks.echomark.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.data.ai.model.ModelState

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val models by viewModel.models.collectAsState(initial = emptyList())
    val llmBackend by viewModel.llmBackend.collectAsState()
    val rediscover by viewModel.rediscover.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AIモデル", style = MaterialTheme.typography.titleLarge)
        Text(
            "要約・タグ付け・チャットにはオンデバイスのモデルを使います。ダウンロードは Wi-Fi 接続時に行われます。",
            style = MaterialTheme.typography.bodySmall
        )

        models.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.spec.displayName, style = MaterialTheme.typography.titleMedium)
                Text(statusLabel(item.state), style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (item.state) {
                        ModelState.NotDownloaded, ModelState.Failed ->
                            Button(onClick = { viewModel.download(item.spec) }) {
                                Text("ダウンロード")
                            }
                        ModelState.Queued, is ModelState.Downloading ->
                            OutlinedButton(onClick = { viewModel.cancel(item.spec) }) {
                                Text("キャンセル")
                            }
                        ModelState.Available ->
                            OutlinedButton(onClick = { viewModel.delete(item.spec) }) {
                                Text("削除")
                            }
                    }
                    // 途中まで取得したファイルも消したい場合など、ダウンロード中でも削除できるようにする
                    if (item.state is ModelState.Downloading || item.state == ModelState.Queued) {
                        OutlinedButton(onClick = { viewModel.delete(item.spec) }) {
                            Text("削除")
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        LlmBackendSection(selected = llmBackend, onSelect = viewModel::setLlmBackend)

        HorizontalDivider()
        RediscoverSection(
            settings = rediscover,
            onEnabledChange = viewModel::setRediscoverEnabled,
            onScheduleChange = viewModel::setRediscoverSchedule
        )
    }
}

/** 「AIの実行場所」。API は準備中のため選択不可。 */
@Composable
private fun LlmBackendSection(selected: LlmBackend, onSelect: (LlmBackend) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("AIの実行場所", style = MaterialTheme.typography.titleLarge)
        Text(
            "既定は端末内(ローカル)での実行です。データは端末の外に送信されません。",
            style = MaterialTheme.typography.bodySmall
        )
        BackendRow("ローカル", selected == LlmBackend.LOCAL, enabled = true) { onSelect(LlmBackend.LOCAL) }
        BackendRow("API(準備中)", selected == LlmBackend.API, enabled = false) { onSelect(LlmBackend.API) }
    }
}

@Composable
private fun BackendRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 「再発見通知」。オンにしたとき(Android 13+)だけ通知権限を要求する。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RediscoverSection(
    settings: RediscoverSettings,
    onEnabledChange: (Boolean) -> Unit,
    onScheduleChange: (DayOfWeek, Int, Int) -> Unit
) {
    val context = LocalContext.current
    var notificationsBlocked by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    fun enable() {
        onEnabledChange(true)
        // API 31/32 には実行時権限が無い。端末設定で通知が無効なら警告を出す
        notificationsBlocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enable() else notificationsBlocked = true
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("再発見通知", style = MaterialTheme.typography.titleLarge)
        Text(
            "30日以上開いていないブックマークを、週に1回お知らせします。",
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("通知する", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = settings.enabled,
                onCheckedChange = { checked ->
                    when {
                        !checked -> {
                            notificationsBlocked = false
                            onEnabledChange(false)
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED ->
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> enable()
                    }
                }
            )
        }
        if (notificationsBlocked) {
            Text(
                "通知が許可されていません。端末の設定でEchoMarkの通知を許可してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (settings.enabled) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day == settings.dayOfWeek,
                        onClick = { onScheduleChange(day, settings.hour, settings.minute) },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)) }
                    )
                }
            }
            OutlinedButton(onClick = { showTimePicker = true }) {
                Text("時刻: %02d:%02d".format(settings.hour, settings.minute))
            }
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = settings.hour,
            initialMinute = settings.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onScheduleChange(settings.dayOfWeek, timeState.hour, timeState.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("キャンセル") }
            },
            text = { TimePicker(state = timeState) }
        )
    }
}

private fun statusLabel(state: ModelState): String = when (state) {
    ModelState.NotDownloaded -> "未ダウンロード"
    ModelState.Queued -> "ダウンロード待機中(Wi-Fi 接続待ち)"
    is ModelState.Downloading -> "ダウンロード中 ${state.percent}%"
    ModelState.Available -> "利用可能"
    ModelState.Failed -> "ダウンロードに失敗しました"
}

package com.unchunks.echomark.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun SettingsScreen(
    onOpenAiSettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val llmBackend by viewModel.llmBackend.collectAsState()
    val rediscover by viewModel.rediscover.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // AI の実行場所・モデル・API キーは専用のサブ画面で設定する
        ListItem(
            headlineContent = { Text("AI 設定") },
            supportingContent = {
                Text(
                    when (llmBackend) {
                        LlmBackend.LOCAL -> "実行場所: 端末内"
                        LlmBackend.API -> "実行場所: クラウド API"
                    }
                )
            },
            leadingContent = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onOpenAiSettings)
        )

        HorizontalDivider()
        RediscoverSection(
            settings = rediscover,
            onEnabledChange = viewModel::setRediscoverEnabled,
            onScheduleChange = viewModel::setRediscoverSchedule
        )
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

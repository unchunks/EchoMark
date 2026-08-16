package com.unchunks.echomark.ui.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("復習リマインダー通知", modifier = Modifier.weight(1f))
            Switch(checked = settings.enabled, onCheckedChange = { viewModel.setEnabled(it) })
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("通知時刻", modifier = Modifier.weight(1f))
            TextButton(
                enabled = settings.enabled,
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> viewModel.setTime(hour, minute) },
                        settings.hour, settings.minute, true
                    ).show()
                }
            ) {
                Text("%02d:%02d".format(settings.hour, settings.minute))
            }
        }
    }
}

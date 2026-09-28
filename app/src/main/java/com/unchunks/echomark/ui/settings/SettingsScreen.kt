package com.unchunks.echomark.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
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
    }
}

private fun statusLabel(state: ModelState): String = when (state) {
    ModelState.NotDownloaded -> "未ダウンロード"
    ModelState.Queued -> "ダウンロード待機中(Wi-Fi 接続待ち)"
    is ModelState.Downloading -> "ダウンロード中 ${state.percent}%"
    ModelState.Available -> "利用可能"
    ModelState.Failed -> "ダウンロードに失敗しました"
}

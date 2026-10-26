package com.unchunks.echomark.ui.settings.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.unchunks.components.embedding.EmbeddingModelProfile
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.provider.EmbeddingModels
import com.unchunks.echomark.ui.settings.japaneseParagraph
import com.unchunks.echomark.ui.settings.shortName

/*
 * AI 設定画面([AiSettingsContent])から開くダイアログ。
 */

/** 取り込むファイルがどのモデルかを選ぶ(ファイルからは判別できず、ベクトルの版としきい値がこれで決まる)。 */
@Composable
internal fun EmbeddingProfileDialog(onSelect: (EmbeddingModelProfile) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(EmbeddingModels.IMPORTABLE.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("取り込むモデルの種類") },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(
                    "ファイルの種類を選んでから、取り込むファイルを選びます。選んだ種類と実際のファイルが違うと、検索の精度が落ちます。",
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
                EmbeddingModels.IMPORTABLE.forEach { profile ->
                    RadioItem(
                        title = profile.displayName,
                        description = "${profile.dimensions} 次元・最大 ${profile.maxInputTokens} トークン",
                        selected = profile == selected,
                        onClick = { selected = profile }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(selected) }) { Text("ファイルを選ぶ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 端末内モデルの削除の確認。消すと、取り込み直すまで端末内での要約・チャットは使えない */
@Composable
internal fun DeleteLocalModelDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("端末内モデルを削除しますか?") },
        text = {
            Text(
                "削除すると、端末内での要約・チャットは再度取り込むまで使えません。",
                style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("削除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** API キーの削除の確認。キーは端末内にしか無いため、消すと入力し直すまで API を使えない */
@Composable
internal fun ClearApiKeyDialog(provider: ApiProvider, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        title = { Text("${provider.shortName} の API キーを削除しますか?") },
        text = {
            Text(
                "削除すると、キーを入力し直すまで ${provider.shortName} の API は使えません。",
                style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("削除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

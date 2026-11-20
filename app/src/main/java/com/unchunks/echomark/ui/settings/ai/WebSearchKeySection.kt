package com.unchunks.echomark.ui.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.common.openUrl

/** Brave Search API のキーを取得する管理画面 */
internal const val BRAVE_SEARCH_DASHBOARD_URL = "https://api-dashboard.search.brave.com/"

/**
 * AI 設定画面の「Web 検索」。Brave Search API のキーの保存・削除・確認と、何が端末の外に出るかの説明。
 * キーの入力欄は画面の中だけで持ち、保存したら消す。
 */
@Composable
internal fun WebSearchKeySection(
    state: WebSearchSettingsUiState,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onTest: (String) -> Unit
) {
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    val paragraph = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph)

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "キーを入れると、チャットの入力欄の地球のボタンで、会話ごとに Web 検索をオンにできます。" +
                "オンにした会話では、質問から作った検索語を Brave Search API に送り、結果の上位のページを読みに行きます" +
                "(それぞれのサイトにも接続します)。保存したブックマークの内容は送りません。",
            style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph)
        )
        FieldLabel("Brave Search API のキー")
        if (state.keyConfigured) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "保存済み(暗号化して端末内に保存)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { showClear = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            }
        }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(if (state.keyConfigured) "新しいキーで上書き" else "キーを入力") },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (keyVisible) "キーを隠す" else "キーを表示"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(keyInput)
                    keyInput = ""
                    keyVisible = false
                },
                enabled = keyInput.isNotBlank()
            ) { Text("保存") }
            OutlinedButton(
                onClick = { onTest(keyInput) },
                enabled = state.test != WebSearchKeyTest.Running && (keyInput.isNotBlank() || state.keyConfigured)
            ) { Text("接続テスト") }
        }
        WebSearchKeyTestResult(state.test)
        Text(
            "接続テストでは検索を1回使います。料金と毎月の無料分は Brave のプランによります。" +
                "キーはバックアップに入れず、「すべてのデータを削除」で設定も消すと一緒に消えます。",
            style = paragraph,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { openUrl(context, BRAVE_SEARCH_DASHBOARD_URL) },
            contentPadding = ButtonDefaults.TextButtonWithIconContentPadding
        ) {
            ButtonIcon(Icons.AutoMirrored.Outlined.OpenInNew)
            Text("Brave Search API のキーを取得")
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("Web 検索のキーを削除") },
            text = { Text("削除すると、チャットで Web を検索できなくなります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClear = false
                        onClear()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun WebSearchKeyTestResult(state: WebSearchKeyTest) {
    when (state) {
        WebSearchKeyTest.Idle -> Unit
        WebSearchKeyTest.Running -> StatusLine(
            icon = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
            text = "確かめています…"
        )
        WebSearchKeyTest.Success -> StatusLine(
            icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            text = "Brave Search API で検索できました"
        )
        is WebSearchKeyTest.Failure -> StatusLine(
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            text = state.message,
            isError = true
        )
    }
}

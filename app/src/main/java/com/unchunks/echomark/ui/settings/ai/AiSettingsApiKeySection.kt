package com.unchunks.echomark.ui.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.ui.common.openUrl
import com.unchunks.echomark.ui.settings.shortName
import kotlinx.coroutines.launch

/*
 * AI 設定画面の「クラウド API のキー」。提供元ごとのキーの保存・削除と接続テスト。
 */

/** 提供元ごとの API キーの保存・削除・接続テスト。どの用途でどの提供元を使うかは用途ごとの設定で選ぶ。 */
@Composable
internal fun ApiKeySection(
    uiState: AiSettingsUiState,
    connectionTest: ConnectionTestState,
    onSelectProvider: (ApiProvider) -> Unit,
    onSaveKey: (ApiProvider, String) -> Unit,
    onClearKey: (ApiProvider) -> Unit,
    onTestConnection: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val provider = uiState.keyProvider

    // キーを入力する提供元の選択
    Column(Modifier.selectableGroup()) {
        ApiProvider.entries.forEach { option ->
            val configured = option in uiState.configuredProviders
            val usedBy = uiState.apiTasks.filter { uiState.setting(it).apiProvider == option }
            RadioItem(
                title = option.displayName,
                description = (if (configured) "API キー設定済み" else "API キー未設定") +
                    if (usedBy.isNotEmpty()) "・${usedBy.joinToString("・") { it.label }}で使用" else "",
                selected = option == provider,
                onClick = { onSelectProvider(option) },
                trailingIcon = if (configured) Icons.Outlined.Key else null
            )
        }
    }

    // キー入力欄は画面内だけで保持する(保存後は消す)。提供元を切り替えたらリセット
    var keyInput by remember(provider) { mutableStateOf("") }
    var keyVisible by remember(provider) { mutableStateOf(false) }
    var showClearKeyDialog by remember(provider) { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FieldLabel("${provider.shortName} の API キー")
        if (uiState.isKeyConfigured) {
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
                    onClick = { showClearKeyDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            }
        }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(if (uiState.isKeyConfigured) "新しいキーで上書き" else "API キーを入力") },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            trailingIcon = {
                Row {
                    IconButton(onClick = {
                        scope.launch {
                            val text = clipboard.getClipEntry()?.clipData
                                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!text.isNullOrBlank()) keyInput = text.trim()
                        }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "貼り付け")
                    }
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (keyVisible) "キーを隠す" else "キーを表示"
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSaveKey(provider, keyInput)
                    keyInput = ""
                    keyVisible = false
                },
                enabled = keyInput.isNotBlank()
            ) { Text("保存") }
            OutlinedButton(
                onClick = { onTestConnection(keyInput) },
                enabled = connectionTest != ConnectionTestState.Running &&
                    (keyInput.isNotBlank() || uiState.isKeyConfigured)
            ) { Text("接続テスト") }
        }
        Text(
            "接続テストには ${uiState.testModel} を使います。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ConnectionTestResult(connectionTest)
        TextButton(
            onClick = { openUrl(context, provider.keyConsoleUrl) },
            contentPadding = ButtonDefaults.TextButtonWithIconContentPadding
        ) {
            ButtonIcon(Icons.AutoMirrored.Outlined.OpenInNew)
            Text("${provider.shortName} の API キーを取得")
        }
    }

    if (showClearKeyDialog) {
        ClearApiKeyDialog(
            provider = provider,
            onConfirm = {
                showClearKeyDialog = false
                onClearKey(provider)
            },
            onDismiss = { showClearKeyDialog = false }
        )
    }
}

@Composable
private fun ConnectionTestResult(state: ConnectionTestState) {
    when (state) {
        ConnectionTestState.Idle -> Unit
        ConnectionTestState.Running -> StatusLine(
            icon = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
            text = "接続を確認中…"
        )
        is ConnectionTestState.Success -> StatusLine(
            icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            text = "${state.provider.displayName} に接続できました"
        )
        is ConnectionTestState.Failure -> StatusLine(
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            text = state.message,
            isError = true
        )
    }
}

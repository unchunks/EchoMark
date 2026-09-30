package com.unchunks.echomark.ui.settings.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.ModelImportState
import com.unchunks.echomark.data.ai.model.formatBytes
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.ui.common.openUrl
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * AI 設定(実行場所・端末内モデルの取り込み・クラウド API のキーとモデル・再処理)。
 * 設定タブの「AI 設定」から開くサブ画面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionTest by viewModel.connectionTest.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }
    val importState = uiState.importState
    LaunchedEffect(importState) {
        if (importState is ModelImportState.Succeeded) {
            snackbarHostState.showSnackbar("「${importState.model.displayName}」を取り込みました")
            viewModel.onImportResultShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            SectionHeader("実行場所")
            BackendSelector(selected = uiState.backend, onSelect = viewModel::setBackend)
            if (uiState.backend == LlmBackend.API) {
                PrivacyNotice(uiState.apiProvider)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("端末内モデル")
            LocalModelSection(
                model = uiState.localModel,
                importState = uiState.importState,
                onImport = viewModel::importModel,
                onCancelImport = viewModel::cancelImport,
                onDelete = viewModel::deleteModel
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("クラウド API")
            ApiSection(
                uiState = uiState,
                connectionTest = connectionTest,
                onSelectProvider = viewModel::setApiProvider,
                onSetModel = viewModel::setApiModel,
                onSaveKey = viewModel::saveApiKey,
                onClearKey = viewModel::clearApiKey,
                onTestConnection = viewModel::testConnection
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("AI 処理")
            ListItem(
                headlineContent = { Text("失敗・準備待ちのブックマークを再処理") },
                supportingContent = { Text("モデルの取り込みや API キーの設定後に、要約・タグ付けをやり直します") },
                leadingContent = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                trailingContent = {
                    OutlinedButton(onClick = viewModel::reprocessPending) { Text("再処理") }
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun BackendSelector(selected: LlmBackend, onSelect: (LlmBackend) -> Unit) {
    Column(Modifier.selectableGroup()) {
        BackendOption(
            title = "端末内(オンデバイス)",
            description = "保存内容は端末の外に送信されません。モデルファイルの取り込みが必要です",
            selected = selected == LlmBackend.LOCAL,
            onClick = { onSelect(LlmBackend.LOCAL) }
        )
        BackendOption(
            title = "クラウド API",
            description = "Claude・Gemini・OpenAI の API を使います。高品質ですが、保存内容が提供元に送信されます",
            selected = selected == LlmBackend.API,
            onClick = { onSelect(LlmBackend.API) }
        )
    }
}

@Composable
private fun BackendOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        // 行全体をタップ対象にし、RadioButton 自体は onClick = null(TalkBack で二重に読まれない)
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
    )
}

@Composable
private fun PrivacyNotice(provider: ApiProvider) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                "ブックマークの本文・要約・チャットの質問が ${provider.displayName} のサーバーに送信されます。" +
                    "送信内容の扱いは各社の規約に従います。API の利用料金は各社のアカウントに請求されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun LocalModelSection(
    model: LocalModelInfo?,
    importState: ModelImportState,
    onImport: (android.net.Uri) -> Unit,
    onCancelImport: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    // .task / .litertlm には標準の MIME タイプが無いため、全ファイルから選ばせて取り込み時に拡張子を検証する
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }

    ListItem(
        headlineContent = { Text(model?.displayName ?: "未取り込み") },
        supportingContent = {
            Text(
                if (model == null) {
                    "端末内で要約・チャットを行うには、モデルファイルを取り込んでください"
                } else {
                    "${formatBytes(model.sizeBytes)} ・ 取り込み: " +
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(model.importedAt))
                }
            )
        },
        leadingContent = { Icon(Icons.Outlined.Memory, contentDescription = null) },
        trailingContent = {
            if (model != null) {
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "モデルを削除")
                }
            }
        }
    )

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (importState) {
            is ModelImportState.Copying -> {
                val fraction = importState.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "取り込み中… ${(fraction * 100).toInt()}%(${formatBytes(importState.copiedBytes)} / ${formatBytes(importState.totalBytes)})",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("取り込み中… ${formatBytes(importState.copiedBytes)}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onCancelImport) { Text("取り込みを中止") }
            }
            else -> {
                if (importState is ModelImportState.Failed) {
                    StatusLine(
                        icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        text = "取り込めませんでした: ${importState.error.userMessage}",
                        isError = true
                    )
                }
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (model == null) "  モデルファイルを取り込む" else "  別のモデルに入れ替える")
                }
            }
        }
        Text(
            "入手方法: Hugging Face の litert-community で公開されている Gemma 3 1B IT" +
                "(例: Gemma3-1B-IT の int4 版 .task)などを端末にダウンロードし、上のボタンで選んでください。\n" +
                "Gemma のダウンロードには、Hugging Face へのログインと利用規約への同意が必要です。\n" +
                "対応形式: .task / .litertlm",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { openUrl(context, MODEL_GUIDE_URL) }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Hugging Face(litert-community)を開く")
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("端末内モデルを削除しますか?") },
            text = { Text("削除すると、端末内での要約・チャットは再度取り込むまで使えません。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiSection(
    uiState: AiSettingsUiState,
    connectionTest: ConnectionTestState,
    onSelectProvider: (ApiProvider) -> Unit,
    onSetModel: (ApiProvider, String) -> Unit,
    onSaveKey: (ApiProvider, String) -> Unit,
    onClearKey: (ApiProvider) -> Unit,
    onTestConnection: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val provider = uiState.apiProvider

    // 提供元ごとの選択
    Column(Modifier.selectableGroup()) {
        ApiProvider.entries.forEach { option ->
            ListItem(
                headlineContent = { Text(option.displayName) },
                supportingContent = {
                    Text(if (option in uiState.configuredProviders) "API キー設定済み" else "API キー未設定")
                },
                leadingContent = { RadioButton(selected = option == provider, onClick = null) },
                trailingContent = {
                    if (option in uiState.configuredProviders) {
                        Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                modifier = Modifier.selectable(
                    selected = option == provider,
                    onClick = { onSelectProvider(option) },
                    role = Role.RadioButton
                )
            )
        }
    }

    // キー入力欄は画面内だけで保持する(保存後は消す)。提供元を切り替えたらリセット
    var keyInput by remember(provider) { mutableStateOf("") }
    var keyVisible by remember(provider) { mutableStateOf(false) }
    var modelInput by remember(provider, uiState.selectedModel) { mutableStateOf(uiState.selectedModel) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("モデル", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            provider.presets.forEach { preset ->
                FilterChip(
                    selected = uiState.selectedModel == preset.id,
                    onClick = { onSetModel(provider, preset.id) },
                    label = { Text(preset.label) }
                )
            }
        }
        OutlinedTextField(
            value = modelInput,
            onValueChange = { modelInput = it },
            label = { Text("モデル ID(自由入力)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            trailingIcon = {
                if (modelInput.trim() != uiState.selectedModel) {
                    TextButton(onClick = { onSetModel(provider, modelInput) }) { Text("適用") }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Text("API キー", style = MaterialTheme.typography.labelLarge)
        if (uiState.isKeyConfigured) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "保存済み(暗号化して端末内に保存)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onClearKey(provider) }) { Text("削除") }
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
        ConnectionTestResult(connectionTest)
        TextButton(onClick = { openUrl(context, provider.keyConsoleUrl) }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  ${provider.displayName} の API キーを取得")
        }
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

@Composable
private fun StatusLine(icon: @Composable () -> Unit, text: String, isError: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

private const val MODEL_GUIDE_URL = "https://huggingface.co/litert-community"

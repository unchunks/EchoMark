package com.unchunks.echomark.ui.settings.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.unchunks.echomark.ui.common.MessageSnackbarEffect
import com.unchunks.echomark.ui.common.openUrl
import com.unchunks.echomark.ui.components.SectionHeader
import com.unchunks.echomark.ui.settings.SettingsItem
import com.unchunks.echomark.ui.settings.SettingsNotice
import com.unchunks.echomark.ui.settings.japaneseParagraph
import com.unchunks.echomark.ui.settings.shortName
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** AI 設定画面の操作。既定は何もしない(スクリーンショット用)。 */
class AiSettingsActions(
    val onBack: () -> Unit = {},
    val onSelectBackend: (LlmBackend) -> Unit = {},
    /** モデルファイルを選ぶ(ファイル選択画面は呼び出し側で開く) */
    val onPickModel: () -> Unit = {},
    val onCancelImport: () -> Unit = {},
    val onDeleteModel: () -> Unit = {},
    val onSelectProvider: (ApiProvider) -> Unit = {},
    val onSetModel: (ApiProvider, String) -> Unit = { _, _ -> },
    val onSaveKey: (ApiProvider, String) -> Unit = { _, _ -> },
    val onClearKey: (ApiProvider) -> Unit = {},
    val onTestConnection: (String) -> Unit = {},
    val onReprocess: () -> Unit = {}
)

/**
 * AI 設定(実行場所・端末内モデルの取り込み・クラウド API のキーとモデル・再処理)。
 * 設定タブの「AI の設定」から開くサブ画面。表示は [AiSettingsContent]。
 */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionTest by viewModel.connectionTest.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    MessageSnackbarEffect(viewModel.message, snackbarHostState, onShown = viewModel::onMessageShown)
    // 取り込みの完了も一度きりの知らせとして出す(表示前に結果を消し、画面に戻ったときに出し直さない)
    val importedMessages = remember(viewModel) {
        viewModel.uiState
            .map { (it.importState as? ModelImportState.Succeeded)?.model?.displayName }
            .distinctUntilChanged()
            .map { name -> name?.let { "「$it」を取り込みました" } }
    }
    MessageSnackbarEffect(importedMessages, snackbarHostState, onShown = viewModel::onImportResultShown)
    // .task / .litertlm には標準の MIME タイプが無いため、全ファイルから選ばせて取り込み時に拡張子を検証する
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importModel(uri)
    }

    AiSettingsContent(
        uiState = uiState,
        connectionTest = connectionTest,
        snackbarHostState = snackbarHostState,
        actions = AiSettingsActions(
            onBack = onBack,
            onSelectBackend = viewModel::setBackend,
            onPickModel = { picker.launch(arrayOf("*/*")) },
            onCancelImport = viewModel::cancelImport,
            onDeleteModel = viewModel::deleteModel,
            onSelectProvider = viewModel::setApiProvider,
            onSetModel = viewModel::setApiModel,
            onSaveKey = viewModel::saveApiKey,
            onClearKey = viewModel::clearApiKey,
            onTestConnection = viewModel::testConnection,
            onReprocess = viewModel::reprocessPending
        )
    )
}

/** AI 設定画面の中身。状態とコールバックを受け取るだけなので、スクリーンショットテストで描画できる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsContent(
    uiState: AiSettingsUiState,
    connectionTest: ConnectionTestState,
    actions: AiSettingsActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("AI の設定") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                scrollBehavior = scrollBehavior
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
            StatusCard(uiState)

            SectionHeader("実行場所")
            BackendSelector(selected = uiState.backend, onSelect = actions.onSelectBackend)
            if (uiState.backend == LlmBackend.API) {
                SettingsNotice(
                    text = "ブックマークの本文・要約・チャットの質問が ${uiState.apiProvider.displayName} のサーバーに送信されます。" +
                        "送信内容の扱いは各社の規約に従い、API の利用料金は各社のアカウントに請求されます。",
                    icon = Icons.Outlined.Info
                )
            }

            SectionDivider()
            SectionHeader("端末内モデル")
            LocalModelSection(
                model = uiState.localModel,
                importState = uiState.importState,
                isSelected = uiState.backend == LlmBackend.LOCAL,
                onPickModel = actions.onPickModel,
                onCancelImport = actions.onCancelImport,
                onDelete = actions.onDeleteModel
            )

            SectionDivider()
            SectionHeader("クラウド API")
            ApiSection(
                uiState = uiState,
                connectionTest = connectionTest,
                onSelectProvider = actions.onSelectProvider,
                onSetModel = actions.onSetModel,
                onSaveKey = actions.onSaveKey,
                onClearKey = actions.onClearKey,
                onTestConnection = actions.onTestConnection
            )

            SectionDivider()
            SectionHeader("AI 処理")
            SettingsItem(
                title = "失敗・準備待ちを再処理",
                icon = Icons.Outlined.Refresh,
                summary = "モデルの取り込みや API キーの設定後に、要約・タグ付けをやり直します",
                onClick = actions.onReprocess
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 4.dp))
}

/** 画面の先頭で、今の設定で AI が使えるか・次に何をすればよいかを伝える。 */
@Composable
private fun StatusCard(uiState: AiSettingsUiState) {
    val provider = uiState.apiProvider.shortName
    val (ready, text) = when (uiState.backend) {
        LlmBackend.LOCAL -> if (uiState.localModel != null) {
            true to "端末内のモデルで動いています"
        } else {
            false to "モデルファイルを取り込むと使えます"
        }
        LlmBackend.API -> if (uiState.isKeyConfigured) {
            true to "$provider の API で動いています"
        } else {
            false to "$provider の API キーを保存すると使えます"
        }
    }
    val container = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyLarge.japaneseParagraph())
        }
    }
}

@Composable
private fun BackendSelector(selected: LlmBackend, onSelect: (LlmBackend) -> Unit) {
    Column(Modifier.selectableGroup()) {
        RadioItem(
            title = "端末内(オンデバイス)",
            description = "保存内容は端末の外に送信されません。モデルファイルの取り込みが必要です",
            selected = selected == LlmBackend.LOCAL,
            onClick = { onSelect(LlmBackend.LOCAL) }
        )
        RadioItem(
            title = "クラウド API",
            description = "Claude・Gemini・OpenAI の API を使います。高品質ですが、保存内容が提供元に送信されます",
            selected = selected == LlmBackend.API,
            onClick = { onSelect(LlmBackend.API) }
        )
    }
}

/** ラジオボタンの行。行全体をタップ対象にし、RadioButton 自体は onClick = null(TalkBack で二重に読まれない)。 */
@Composable
private fun RadioItem(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = null
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(description, style = MaterialTheme.typography.bodyMedium.japaneseParagraph())
        },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = trailingIcon?.let {
            { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
    )
}

@Composable
private fun LocalModelSection(
    model: LocalModelInfo?,
    importState: ModelImportState,
    isSelected: Boolean,
    onPickModel: () -> Unit,
    onCancelImport: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    SettingsItem(
        title = model?.displayName ?: "未取り込み",
        icon = Icons.Outlined.Memory,
        iconTint = if (model != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        summary = if (model == null) {
            if (isSelected) "端末内で要約・チャットを行うには、モデルファイルを取り込んでください" else "取り込むと、端末内でも AI を使えます"
        } else {
            "${formatBytes(model.sizeBytes)}・取り込み: " +
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(model.importedAt))
        },
        trailing = if (model != null) {
            {
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "モデルを削除")
                }
            }
        } else {
            null
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
                        icon = {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        text = "取り込めませんでした: ${importState.error.userMessage}",
                        isError = true
                    )
                }
                Button(onClick = onPickModel) {
                    ButtonIcon(Icons.Outlined.UploadFile)
                    Text(if (model == null) "モデルファイルを取り込む" else "別のモデルに入れ替える")
                }
            }
        }
        Text(
            "入手方法: Hugging Face の litert-community で公開されている Gemma 3 1B IT" +
                "(例: Gemma3-1B-IT の int4 版 .task)などを端末にダウンロードし、上のボタンで選んでください。" +
                "Gemma のダウンロードには、Hugging Face へのログインと利用規約への同意が必要です。\n" +
                "対応形式: .task / .litertlm",
            style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { openUrl(context, MODEL_GUIDE_URL) },
            contentPadding = ButtonDefaults.TextButtonWithIconContentPadding
        ) {
            ButtonIcon(Icons.AutoMirrored.Outlined.OpenInNew)
            Text("Hugging Face(litert-community)を開く")
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("端末内モデルを削除しますか?") },
            text = {
                Text(
                    "削除すると、端末内での要約・チャットは再度取り込むまで使えません。",
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
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
            val configured = option in uiState.configuredProviders
            RadioItem(
                title = option.displayName,
                description = if (configured) "API キー設定済み" else "API キー未設定",
                selected = option == provider,
                onClick = { onSelectProvider(option) },
                trailingIcon = if (configured) Icons.Outlined.Key else null
            )
        }
    }

    // キー入力欄は画面内だけで保持する(保存後は消す)。提供元を切り替えたらリセット
    var keyInput by remember(provider) { mutableStateOf("") }
    var keyVisible by remember(provider) { mutableStateOf(false) }
    var modelInput by remember(provider, uiState.selectedModel) { mutableStateOf(uiState.selectedModel) }
    var showClearKeyDialog by remember(provider) { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FieldLabel("モデル")
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

        Spacer(Modifier.size(4.dp))
        FieldLabel("API キー")
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

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ButtonIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
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
            style = MaterialTheme.typography.bodyMedium.japaneseParagraph(),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

private const val MODEL_GUIDE_URL = "https://huggingface.co/litert-community"

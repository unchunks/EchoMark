package com.unchunks.echomark.ui.settings.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.data.ai.model.ModelImportState
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.EmbeddingModelProfile
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.ui.common.MessageSnackbarEffect
import com.unchunks.echomark.ui.common.readableWidth
import com.unchunks.echomark.ui.components.SectionHeader
import com.unchunks.echomark.ui.settings.SettingsItem
import com.unchunks.echomark.ui.settings.SettingsNotice
import com.unchunks.echomark.ui.settings.japaneseParagraph
import com.unchunks.echomark.ui.settings.shortName
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** AI 設定画面の操作。既定は何もしない(スクリーンショット用)。 */
class AiSettingsActions(
    val onBack: () -> Unit = {},
    val onSelectBackend: (AiTask, LlmBackend) -> Unit = { _, _ -> },
    /** モデルファイルを選ぶ(ファイル選択画面は呼び出し側で開く) */
    val onPickModel: () -> Unit = {},
    val onCancelImport: () -> Unit = {},
    val onDeleteModel: () -> Unit = {},
    /** 埋め込みモデルのファイルを選ぶ(取り込むモデルの種類を渡す。ファイル選択画面は呼び出し側で開く) */
    val onPickEmbeddingModel: (EmbeddingModelProfile) -> Unit = {},
    val onCancelEmbeddingImport: () -> Unit = {},
    /** 取り込んだ埋め込みモデルを消して、同梱のモデルに戻す */
    val onRevertEmbeddingModel: () -> Unit = {},
    val onSelectProvider: (AiTask, ApiProvider) -> Unit = { _, _ -> },
    val onSetModel: (AiTask, ApiProvider, String) -> Unit = { _, _, _ -> },
    /** API キーを入力する提供元を選ぶ */
    val onSelectKeyProvider: (ApiProvider) -> Unit = {},
    val onSaveKey: (ApiProvider, String) -> Unit = { _, _ -> },
    val onClearKey: (ApiProvider) -> Unit = {},
    val onTestConnection: (String) -> Unit = {},
    val onSetSendFilesToCloud: (Boolean) -> Unit = {},
    val onSetLocalLlmUseGpu: (Boolean) -> Unit = {},
    val onReprocess: () -> Unit = {}
)

/**
 * AI 設定(用途ごとの実行場所・提供元・モデル、端末内モデルの取り込み、クラウド API のキー、再処理)。
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
    val embeddingImportedMessages = remember(viewModel) {
        viewModel.uiState
            .map { (it.embeddingImportState as? ModelImportState.Succeeded)?.model?.displayName }
            .distinctUntilChanged()
            .map { name -> name?.let { "埋め込みモデル「$it」を取り込みました。検索インデックスを作り直します" } }
    }
    MessageSnackbarEffect(embeddingImportedMessages, snackbarHostState, onShown = viewModel::onEmbeddingImportResultShown)
    // 埋め込みモデルは、取り込む種類を選んでからファイルを選ぶ(画面の再生成でも選んだ種類を失わない)
    var pendingEmbeddingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val embeddingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val profile = pendingEmbeddingProfileId?.let { EmbeddingModelProfile.findById(it) }
        pendingEmbeddingProfileId = null
        if (uri != null && profile != null) viewModel.importEmbeddingModel(uri, profile)
    }
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
            onPickEmbeddingModel = { profile ->
                pendingEmbeddingProfileId = profile.id
                embeddingPicker.launch(arrayOf("*/*"))
            },
            onCancelEmbeddingImport = viewModel::cancelEmbeddingImport,
            onRevertEmbeddingModel = viewModel::revertEmbeddingModel,
            onSelectProvider = viewModel::setApiProvider,
            onSetModel = viewModel::setApiModel,
            onSelectKeyProvider = viewModel::selectKeyProvider,
            onSaveKey = viewModel::saveApiKey,
            onClearKey = viewModel::clearApiKey,
            onTestConnection = viewModel::testConnection,
            onSetSendFilesToCloud = viewModel::setSendFilesToCloud,
            onSetLocalLlmUseGpu = viewModel::setLocalLlmUseGpu,
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
                // スクロール領域は画面幅いっぱいのまま、中身だけを読みやすい幅にして中央に寄せる
                .readableWidth()
                .padding(bottom = 24.dp)
        ) {
            StatusCard(uiState)

            SectionHeader("用途ごとの AI")
            AiTask.entries.forEach { task ->
                TaskSettingCard(
                    task = task,
                    uiState = uiState,
                    onSelectBackend = { actions.onSelectBackend(task, it) },
                    onSelectProvider = { actions.onSelectProvider(task, it) },
                    onSetModel = { provider, model -> actions.onSetModel(task, provider, model) }
                )
            }
            if (uiState.analyzesSeparately) {
                SettingsNotice(
                    text = "タグ付けと要約に別の AI を選んでいるため、ブックマーク1件につき AI を2回呼び出します" +
                        "(処理に時間がかかり、クラウド API ならその分の料金がかかります)。",
                    icon = Icons.Outlined.Info
                )
            }
            if (uiState.apiTasks.isNotEmpty()) {
                SettingsNotice(text = cloudNotice(uiState), icon = Icons.Outlined.Info)
            }
            if (uiState.analysisApiProviders.isNotEmpty()) {
                SendFilesToCloudSetting(
                    providers = uiState.analysisApiProviders,
                    checked = uiState.sendFilesToCloud,
                    onCheckedChange = actions.onSetSendFilesToCloud
                )
            }

            SectionDivider()
            SectionHeader("端末内モデル")
            LocalModelSection(
                model = uiState.localModel,
                importState = uiState.importState,
                isSelected = uiState.usesLocal,
                onPickModel = actions.onPickModel,
                onCancelImport = actions.onCancelImport,
                onDelete = actions.onDeleteModel
            )
            if (uiState.localModel != null) {
                LocalLlmGpuSetting(checked = uiState.localLlmUseGpu, onCheckedChange = actions.onSetLocalLlmUseGpu)
            }

            SectionDivider()
            SectionHeader("検索用の埋め込みモデル")
            EmbeddingModelSection(
                model = uiState.embeddingModel,
                importState = uiState.embeddingImportState,
                onPickModel = actions.onPickEmbeddingModel,
                onCancelImport = actions.onCancelEmbeddingImport,
                onRevert = actions.onRevertEmbeddingModel
            )
            uiState.embeddingProgress?.takeIf { !it.isComplete }?.let { EmbeddingIndexProgress(it) }

            if (CLOUD_AI_ENABLED) {
                SectionDivider()
                SectionHeader("クラウド API のキー")
                ApiKeySection(
                    uiState = uiState,
                    connectionTest = connectionTest,
                    onSelectProvider = actions.onSelectKeyProvider,
                    onSaveKey = actions.onSaveKey,
                    onClearKey = actions.onClearKey,
                    onTestConnection = actions.onTestConnection
                )
            }

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

/** 画面の先頭で、用途ごとに AI が使えるか・次に何をすればよいかを伝える。 */
@Composable
private fun StatusCard(uiState: AiSettingsUiState) {
    val ready = AiTask.entries.all(uiState::isReady)
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (ready) "すべての用途で AI を使えます" else "設定が必要な用途があります",
                    style = MaterialTheme.typography.bodyLarge.japaneseParagraph()
                )
                AiTask.entries.forEach { task ->
                    Text(
                        "${task.label}: ${taskStatusText(uiState, task)}",
                        style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                    )
                }
            }
        }
    }
}

/** 用途ごとの状態の説明。例: 「Claude の API(claude-opus-5-5)で動きます」「モデルファイルを取り込むと使えます」 */
internal fun taskStatusText(uiState: AiSettingsUiState, task: AiTask): String {
    val setting = uiState.setting(task)
    val provider = setting.apiProvider.shortName
    val ready = uiState.isReady(task)
    return when (setting.backend) {
        LlmBackend.LOCAL -> if (ready) "端末内のモデルで動きます" else "モデルファイルを取り込むと使えます"
        LlmBackend.API -> if (ready) "$provider の API(${setting.apiModel})で動きます" else "$provider の API キーを保存すると使えます"
    }
}

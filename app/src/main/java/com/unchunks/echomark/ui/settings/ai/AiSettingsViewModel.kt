package com.unchunks.echomark.ui.settings.ai

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.data.ai.api.ApiLlmProvider
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.ModelImportState
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.toLlmUserMessage
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 接続テストの状態。 */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Running : ConnectionTestState
    data class Success(val provider: ApiProvider) : ConnectionTestState
    data class Failure(val message: String) : ConnectionTestState
}

data class AiSettingsUiState(
    val backend: LlmBackend = LlmBackend.LOCAL,
    val localModel: LocalModelInfo? = null,
    val importState: ModelImportState = ModelImportState.Idle,
    val apiProvider: ApiProvider = ApiProvider.CLAUDE,
    /** 提供元ごとのモデル ID */
    val apiModels: Map<ApiProvider, String> = emptyMap(),
    /** API キーが保存済みの提供元 */
    val configuredProviders: Set<ApiProvider> = emptySet(),
    /** クラウド API にファイル(画像・PDF・音声・動画)そのものも送って解析するか */
    val sendFilesToCloud: Boolean = true
) {
    val selectedModel: String get() = apiModels[apiProvider] ?: apiProvider.defaultModel
    val isKeyConfigured: Boolean get() = apiProvider in configuredProviders
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val modelManager: ModelManager,
    private val apiLlmProvider: ApiLlmProvider,
    private val bookmarkRepository: BookmarkRepository
) : ViewModel() {

    val uiState: StateFlow<AiSettingsUiState> = combine(
        appSettings.llmBackend,
        combine(modelManager.installedModel, modelManager.importState, ::Pair),
        appSettings.apiProvider,
        combine(appSettings.apiModels, appSettings.sendFilesToCloud, ::Pair),
        apiKeyRepository.configuredProviders
    ) { backend, (model, import), provider, (models, sendFiles), configured ->
        AiSettingsUiState(
            backend = backend,
            localModel = model,
            importState = import,
            apiProvider = provider,
            apiModels = models,
            configuredProviders = configured,
            sendFilesToCloud = sendFiles
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    private val _connectionTest = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    /** Snackbar で1回だけ出すメッセージ。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var testJob: Job? = null

    fun setBackend(backend: LlmBackend) {
        viewModelScope.launch { appSettings.setLlmBackend(backend) }
    }

    fun setApiProvider(provider: ApiProvider) {
        _connectionTest.value = ConnectionTestState.Idle
        viewModelScope.launch { appSettings.setApiProvider(provider) }
    }

    fun setApiModel(provider: ApiProvider, modelId: String) {
        _connectionTest.value = ConnectionTestState.Idle
        viewModelScope.launch { appSettings.setApiModel(provider, modelId) }
    }

    fun setSendFilesToCloud(enabled: Boolean) {
        viewModelScope.launch { appSettings.setSendFilesToCloud(enabled) }
    }

    /** キーを暗号化して保存する。入力欄の平文は呼び出し側で消すこと。 */
    fun saveApiKey(provider: ApiProvider, apiKey: String) {
        if (apiKey.isBlank()) return
        _connectionTest.value = ConnectionTestState.Idle
        viewModelScope.launch {
            apiKeyRepository.setKey(provider, apiKey)
            _message.value = "${provider.displayName} の API キーを保存しました"
        }
    }

    fun clearApiKey(provider: ApiProvider) {
        _connectionTest.value = ConnectionTestState.Idle
        viewModelScope.launch {
            apiKeyRepository.clearKey(provider)
            _message.value = "${provider.displayName} の API キーを削除しました"
        }
    }

    /**
     * 短いリクエストで疎通を確認する。
     * @param typedKey 入力中のキー。空なら保存済みのキーで試す
     */
    fun testConnection(typedKey: String) {
        if (_connectionTest.value == ConnectionTestState.Running) return
        val state = uiState.value
        val provider = state.apiProvider
        _connectionTest.value = ConnectionTestState.Running
        testJob = viewModelScope.launch {
            _connectionTest.value = try {
                apiLlmProvider.testConnection(provider, state.selectedModel, typedKey.ifBlank { null })
                ConnectionTestState.Success(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 例外メッセージにキーは含めていない(LlmException 側で伏せている)
                Timber.w("接続テストに失敗: provider=$provider (${e.javaClass.simpleName})")
                ConnectionTestState.Failure(e.toLlmUserMessage("接続できませんでした"))
            }
        }
    }

    fun importModel(uri: Uri) = modelManager.startImport(uri)

    fun cancelImport() = modelManager.cancelImport()

    fun deleteModel() {
        modelManager.deleteModel()
        _message.value = "端末内モデルを削除しました"
    }

    /** 取り込み結果(成功・失敗)の表示が済んだ。 */
    fun onImportResultShown() = modelManager.clearImportResult()

    /** 失敗・準備待ちのブックマークの AI 処理をやり直す。 */
    fun reprocessPending() {
        viewModelScope.launch {
            val count = bookmarkRepository.enqueueFailedAndWaitingProcessing()
            _message.value = if (count == 0) "再処理が必要なブックマークはありません" else "${count} 件のブックマークを再処理します"
        }
    }

    fun onMessageShown() {
        _message.update { null }
    }
}

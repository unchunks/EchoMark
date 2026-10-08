package com.unchunks.echomark.ui.settings.ai

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.data.ai.api.ApiLlmProvider
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.ModelImportState
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.model.EmbeddingProgress
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.toLlmUserMessage
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    /** 用途ごとの設定(実行場所・提供元・モデル) */
    val tasks: Map<AiTask, AiTaskSetting> = AiTask.entries.associateWith { AiTaskSetting() },
    val localModel: LocalModelInfo? = null,
    val importState: ModelImportState = ModelImportState.Idle,
    /** 検索インデックス(埋め込み)の更新状況。まだ読めていなければ null */
    val embeddingProgress: EmbeddingProgress? = null,
    /** API キーを入力・確認する提供元(キーは提供元ごとに1つで、用途の選択とは別) */
    val keyProvider: ApiProvider = ApiProvider.CLAUDE,
    /** API キーが保存済みの提供元 */
    val configuredProviders: Set<ApiProvider> = emptySet(),
    /** クラウド API にファイル(画像・PDF・音声・動画)そのものも送って解析するか */
    val sendFilesToCloud: Boolean = true
) {
    fun setting(task: AiTask): AiTaskSetting = tasks[task] ?: AiTaskSetting()

    /** [task] に選んだ実行場所に必要なもの(端末内モデル・API キー)がそろっているか */
    fun isReady(task: AiTask): Boolean {
        val setting = setting(task)
        return when (setting.backend) {
            LlmBackend.LOCAL -> localModel != null
            LlmBackend.API -> setting.apiProvider in configuredProviders
        }
    }

    /** 端末内で動かす用途があるか */
    val usesLocal: Boolean get() = AiTask.entries.any { setting(it).backend == LlmBackend.LOCAL }

    /** クラウド API を使う用途 */
    val apiTasks: List<AiTask> get() = AiTask.entries.filter { setting(it).backend == LlmBackend.API }

    /** ファイルを送りうる用途(タグ付け・要約)で使う提供元。重複なし */
    val analysisApiProviders: List<ApiProvider>
        get() = listOf(AiTask.SUMMARY, AiTask.TAGGING).map(::setting)
            .filter { it.backend == LlmBackend.API }
            .map { it.apiProvider }
            .distinct()

    /** タグ付けと要約が別の AI で、1件につき2回推論するか */
    val analyzesSeparately: Boolean get() = !setting(AiTask.TAGGING).sameEngineAs(setting(AiTask.SUMMARY))

    val isKeyConfigured: Boolean get() = keyProvider in configuredProviders

    /** 接続テストに使うモデル。[keyProvider] を選んでいる用途があればそのモデル、無ければ提供元の既定 */
    val testModel: String
        get() = apiTasks.map(::setting).firstOrNull { it.apiProvider == keyProvider }?.apiModel
            ?: keyProvider.defaultModel
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val modelManager: ModelManager,
    private val apiLlmProvider: ApiLlmProvider,
    private val bookmarkRepository: BookmarkRepository
) : ViewModel() {

    /** キーを入力する提供元として選ばれたもの。未選択なら、クラウド API を使う用途の提供元を出す */
    private val selectedKeyProvider = MutableStateFlow<ApiProvider?>(null)

    val uiState: StateFlow<AiSettingsUiState> = combine(
        appSettings.aiTaskSettings,
        combine(modelManager.installedModel, modelManager.importState, embeddingProgressFlow(), ::Triple),
        selectedKeyProvider,
        appSettings.sendFilesToCloud,
        apiKeyRepository.configuredProviders
    ) { tasks, (model, import, progress), keyProvider, sendFiles, configured ->
        AiSettingsUiState(
            tasks = tasks,
            localModel = model,
            importState = import,
            embeddingProgress = progress,
            keyProvider = keyProvider
                ?: AiTask.entries.mapNotNull { tasks[it] }.firstOrNull { it.backend == LlmBackend.API }?.apiProvider
                ?: ApiProvider.CLAUDE,
            configuredProviders = configured,
            sendFilesToCloud = sendFiles
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    /** 検索インデックスの更新状況を定期的に読む(画面を見ている間だけ動く)。 */
    private fun embeddingProgressFlow(): Flow<EmbeddingProgress?> = flow {
        while (true) {
            emit(
                try {
                    bookmarkRepository.getEmbeddingProgress()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "検索インデックスの状況を取得できない")
                    null
                }
            )
            delay(EMBEDDING_PROGRESS_POLL_MILLIS)
        }
    }

    private val _connectionTest = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    /** Snackbar で1回だけ出すメッセージ。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var testJob: Job? = null

    fun setBackend(task: AiTask, backend: LlmBackend) {
        viewModelScope.launch { appSettings.setLlmBackend(task, backend) }
    }

    /** [task] の提供元を選ぶ。キーの入力欄もその提供元に合わせる(未設定ならすぐ入力できるように) */
    fun setApiProvider(task: AiTask, provider: ApiProvider) {
        selectKeyProvider(provider)
        viewModelScope.launch { appSettings.setApiProvider(task, provider) }
    }

    fun setApiModel(task: AiTask, provider: ApiProvider, modelId: String) {
        _connectionTest.value = ConnectionTestState.Idle
        viewModelScope.launch { appSettings.setApiModel(task, provider, modelId) }
    }

    /** API キーを入力・確認する提供元を選ぶ。 */
    fun selectKeyProvider(provider: ApiProvider) {
        if (uiState.value.keyProvider != provider) _connectionTest.value = ConnectionTestState.Idle
        selectedKeyProvider.value = provider
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
        val provider = state.keyProvider
        _connectionTest.value = ConnectionTestState.Running
        testJob = viewModelScope.launch {
            _connectionTest.value = try {
                apiLlmProvider.testConnection(provider, state.testModel, typedKey.ifBlank { null })
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

    private companion object {
        const val EMBEDDING_PROGRESS_POLL_MILLIS = 2_000L
    }
}

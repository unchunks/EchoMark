package com.unchunks.echomark.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.BuildConfig
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BackupImportSummary
import com.unchunks.echomark.domain.repository.DataManagementRepository
import com.unchunks.echomark.domain.repository.DataOperationException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.worker.RediscoverScheduleController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.DayOfWeek
import java.util.Locale
import javax.inject.Inject

/** 設定画面の「AI」行に出す、今の AI の状態。 */
data class AiSummary(
    /** 用途ごとの設定 */
    val tasks: Map<AiTask, AiTaskSetting> = AiTask.entries.associateWith { AiTaskSetting() },
    /** 取り込み済みの端末内モデルの表示名(拡張子なし)。未取り込みなら null */
    val localModelName: String? = null,
    /** API キーが保存済みの提供元 */
    val configuredProviders: Set<ApiProvider> = emptySet()
) {
    private fun setting(task: AiTask): AiTaskSetting = tasks[task] ?: AiTaskSetting()

    private fun isReady(setting: AiTaskSetting): Boolean = when (setting.backend) {
        LlmBackend.LOCAL -> localModelName != null
        LlmBackend.API -> setting.apiProvider in configuredProviders
    }

    /** タグ付け・要約・チャットのすべてをすぐ使える状態か */
    val isReady: Boolean get() = AiTask.entries.all { isReady(setting(it)) }

    /** 使っている実行場所(用途ごとに違えば両方) */
    val backends: Set<LlmBackend> get() = AiTask.entries.mapTo(mutableSetOf()) { setting(it).backend }

    /**
     * 全用途が同じ AI なら 例: 「端末内: gemma3-1b-it-int4」「Claude API(claude-opus-5-5)」「未設定(…)」。
     * 用途ごとに違えば 例: 「タグ付け: 端末内 / 要約: Claude(claude-opus-5-5) / チャット: 未設定(…)」
     */
    val label: String
        get() {
            val first = setting(AiTask.entries.first())
            if (AiTask.entries.all { setting(it).sameEngineAs(first) }) return fullLabel(first)
            return AiTask.entries.joinToString(" / ") { "${it.label}: ${shortLabel(setting(it))}" }
        }

    private fun fullLabel(setting: AiTaskSetting): String = when (setting.backend) {
        LlmBackend.LOCAL -> localModelName?.let { "端末内: $it" } ?: "未設定(端末内モデルが未取り込み)"
        LlmBackend.API -> if (isReady(setting)) {
            "${setting.apiProvider.shortName} API(${setting.apiModel})"
        } else {
            "未設定(${setting.apiProvider.shortName} の API キーが未入力)"
        }
    }

    private fun shortLabel(setting: AiTaskSetting): String = when {
        !isReady(setting) -> fullLabel(setting)
        setting.backend == LlmBackend.LOCAL -> "端末内"
        else -> "${setting.apiProvider.shortName}(${setting.apiModel})"
    }
}

/** 「Claude (Anthropic)」→「Claude」 */
internal val ApiProvider.shortName: String get() = displayName.substringBefore(" (")

/** バックアップ・全削除の進行と結果(ダイアログで出す)。 */
sealed interface DataOperationState {
    data object Idle : DataOperationState
    data class Running(val message: String) : DataOperationState
    data class Imported(val summary: BackupImportSummary) : DataOperationState
    data class Failed(val title: String, val message: String) : DataOperationState
}

data class SettingsUiState(
    val isLoaded: Boolean = false,
    val ai: AiSummary = AiSummary(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val rediscover: RediscoverSettings = RediscoverSettings(),
    /** 計算中は null */
    val storage: StorageUsage? = null,
    val dataOperation: DataOperationState = DataOperationState.Idle,
    val versionLabel: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettings: AppSettingsRepository,
    apiKeyRepository: ApiKeyRepository,
    modelManager: ModelManager,
    private val dataRepository: DataManagementRepository,
    private val scheduleController: RediscoverScheduleController
) : ViewModel() {

    private val storage = MutableStateFlow<StorageUsage?>(null)
    private val dataOperation = MutableStateFlow<DataOperationState>(DataOperationState.Idle)

    private val aiSummary = combine(
        appSettings.aiTaskSettings,
        apiKeyRepository.configuredProviders,
        modelManager.installedModel
    ) { tasks, configured, model ->
        AiSummary(
            tasks = tasks,
            localModelName = model?.displayName?.let(::modelDisplayName),
            configuredProviders = configured
        )
    }

    private val display = combine(appSettings.themeMode, appSettings.dynamicColor, ::Pair)

    val uiState: StateFlow<SettingsUiState> = combine(
        aiSummary,
        display,
        appSettings.rediscoverSettings,
        storage,
        dataOperation
    ) { ai, (themeMode, dynamicColor), rediscover, storage, operation ->
        SettingsUiState(
            isLoaded = true,
            ai = ai,
            themeMode = themeMode,
            dynamicColor = dynamicColor,
            rediscover = rediscover,
            storage = storage,
            dataOperation = operation,
            versionLabel = VERSION_LABEL
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Snackbar で1回だけ出すメッセージ。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshStorage()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettings.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { appSettings.setDynamicColor(enabled) }
    }

    /** 再発見通知のオン/オフ。オンなら現在の曜日・時刻でスケジュールし、オフなら取り消す。 */
    fun setRediscoverEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setRediscoverEnabled(enabled)
            applySchedule()
        }
    }

    fun setRediscoverSchedule(dayOfWeek: DayOfWeek, hour: Int, minute: Int) {
        viewModelScope.launch {
            appSettings.setRediscoverSchedule(dayOfWeek, hour, minute)
            applySchedule()
        }
    }

    private suspend fun applySchedule() {
        scheduleController.apply(appSettings.rediscoverSettings.first())
    }

    /** ストレージ使用量を計算し直す(画面を開いたとき・データを変えたとき)。 */
    fun refreshStorage() {
        viewModelScope.launch {
            storage.value = try {
                dataRepository.storageUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ストレージ使用量を取得できない")
                null
            }
        }
    }

    fun exportBackup(uri: String) = runDataOperation(
        runningMessage = "バックアップを書き出しています…",
        failureTitle = "書き出せませんでした"
    ) {
        val summary = dataRepository.exportBackup(uri)
        _message.value = "${summary.bookmarks} 件のブックマークと ${summary.conversations} 件の会話を書き出しました"
        DataOperationState.Idle
    }

    fun importBackup(uri: String) = runDataOperation(
        runningMessage = "バックアップを読み込んでいます…",
        failureTitle = "読み込めませんでした"
    ) {
        DataOperationState.Imported(dataRepository.importBackup(uri))
    }

    /** すべてのデータを削除する。[resetSettings] なら設定と API キーも初期化する。 */
    fun deleteAllData(resetSettings: Boolean) = runDataOperation(
        runningMessage = "削除しています…",
        failureTitle = "削除できませんでした"
    ) {
        dataRepository.deleteAllData(resetSettings)
        _message.value = if (resetSettings) "すべてのデータと設定を初期化しました" else "すべてのデータを削除しました"
        DataOperationState.Idle
    }

    private fun runDataOperation(
        runningMessage: String,
        failureTitle: String,
        block: suspend () -> DataOperationState
    ) {
        if (dataOperation.value is DataOperationState.Running) return
        dataOperation.value = DataOperationState.Running(runningMessage)
        viewModelScope.launch {
            dataOperation.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DataOperationException) {
                DataOperationState.Failed(failureTitle, e.message ?: "もう一度お試しください")
            } catch (e: Exception) {
                Timber.w(e, "データ操作に失敗")
                DataOperationState.Failed(failureTitle, "予期しないエラーが発生しました。もう一度お試しください")
            }
            refreshStorage()
        }
    }

    /** 読み込み結果・エラーのダイアログを閉じた。 */
    fun onDataResultDismissed() {
        dataOperation.update { if (it is DataOperationState.Running) it else DataOperationState.Idle }
    }

    fun onMessageShown() {
        _message.value = null
    }

    private companion object {
        val VERSION_LABEL = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
}

/** 取り込み元のファイル名から拡張子を除いた表示名。 */
internal fun modelDisplayName(fileName: String): String =
    fileName.substringBeforeLast('.').ifBlank { fileName }

/** 12 KB / 3.4 MB / 1.2 GB のような表示。小さいサイズも 0 にならないようにする。 */
internal fun formatStorageSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        bytes <= 0 -> "0 KB"
        gb >= 1 -> String.format(Locale.ROOT, "%.1f GB", gb)
        mb >= 10 -> String.format(Locale.ROOT, "%.0f MB", mb)
        mb >= 1 -> String.format(Locale.ROOT, "%.1f MB", mb)
        else -> String.format(Locale.ROOT, "%.0f KB", kb.coerceAtLeast(1.0))
    }
}

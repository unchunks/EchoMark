package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.data.ai.model.InstalledModels
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.LocalModelLoadFailure
import com.unchunks.echomark.data.ai.model.ModelImportError
import com.unchunks.echomark.data.ai.model.ModelImportState
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.aiTasks
import com.unchunks.echomark.ui.settings.ai.AiSettingsActions
import com.unchunks.echomark.ui.settings.ai.AiSettingsContent
import com.unchunks.echomark.ui.settings.ai.AiSettingsUiState
import com.unchunks.echomark.ui.settings.ai.ClearApiKeyDialog
import com.unchunks.echomark.ui.settings.ai.ConnectionTestState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** AI 設定サブ画面の主な状態(縦長の画面で全体を撮る)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h3400dp-xhdpi")
class AiSettingsScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val model = LocalModelInfo(
        fileName = "local_llm.litertlm",
        displayName = "Gemma3-1B-IT_q4_ekv4096.litertlm",
        sizeBytes = 555_000_000,
        importedAt = 1_790_000_000_000L
    )

    private val legacyModel = LocalModelInfo(
        fileName = "local_llm.task",
        displayName = "gemma3-1b-it-int4.task",
        sizeBytes = 555_000_000,
        importedAt = 1_790_000_000_000L
    )

    private var originalTimeZone: TimeZone = TimeZone.getDefault()

    // 取り込み日時の表示を実行環境のタイムゾーンに依存させない
    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    @After
    fun tearDown() = TimeZone.setDefault(originalTimeZone)

    @Composable
    private fun Screen(state: AiSettingsUiState, connectionTest: ConnectionTestState = ConnectionTestState.Idle) {
        Box(Modifier.height(3300.dp)) {
            AiSettingsContent(uiState = state, connectionTest = connectionTest, actions = AiSettingsActions())
        }
    }

    /** 初回: 端末内・モデル未取り込み */
    @Test
    fun localWithoutModel() = screenshot.captureLightDark("ai_settings_local_no_model") {
        Screen(AiSettingsUiState())
    }

    /** 端末内・取り込み済みで、別のモデルを取り込み中 */
    @Test
    fun localImporting() = screenshot.captureLightDark("ai_settings_local_importing") {
        Screen(
            AiSettingsUiState(
                tasks = aiTasks(LlmBackend.LOCAL),
                localModels = InstalledModels(listOf(model)),
                importState = ModelImportState.Copying(copiedBytes = 230_000_000, totalBytes = 555_000_000)
            )
        )
    }

    /** 以前の版で取り込んだ旧形式(.task)のモデル。LiteRT-LM で動かし、.litertlm 版の取り込み直しを案内する */
    @Test
    fun localLegacyTaskModel() = screenshot.captureLightDark("ai_settings_local_legacy_task") {
        Screen(
            AiSettingsUiState(
                tasks = aiTasks(LlmBackend.LOCAL),
                localModels = InstalledModels(listOf(legacyModel))
            )
        )
    }

    /** 端末内モデルを2つ取り込み、チャットだけ別のモデルを選んだ(用途ごとのモデルの選択と、読み込み直しの説明) */
    @Test
    fun localMultipleModels() = screenshot.captureLightDark("ai_settings_local_multiple_models") {
        val qwen = LocalModelInfo("local_llm_2.litertlm", "Qwen3-0.6B.litertlm", 600_000_000, 1_790_100_000_000L)
        val tasks = aiTasks(LlmBackend.LOCAL)
        Screen(
            AiSettingsUiState(
                tasks = tasks + (AiTask.CHAT to tasks.getValue(AiTask.CHAT).copy(localModelId = qwen.id)),
                localModels = InstalledModels(listOf(model, qwen), defaultId = model.id)
            )
        )
    }

    /** 取り込んだモデルを LiteRT-LM で読み込めなかった */
    @Test
    fun localModelLoadFailed() = screenshot.captureLightDark("ai_settings_local_load_failed") {
        Screen(
            AiSettingsUiState(
                tasks = aiTasks(LlmBackend.LOCAL),
                localModels = InstalledModels(listOf(legacyModel)),
                localModelLoadFailures = mapOf(legacyModel.id to LocalModelLoadFailure(legacyModel, "Failed to select jinja prompt template"))
            )
        )
    }

    /** クラウド API・キー設定済み・接続テスト成功 */
    @Test
    fun apiReady() = screenshot.captureLightDark("ai_settings_api_ready") {
        Screen(
            AiSettingsUiState(
                tasks = aiTasks(LlmBackend.API, ApiProvider.CLAUDE),
                localModels = InstalledModels(listOf(model)),
                keyProvider = ApiProvider.CLAUDE,
                configuredProviders = setOf(ApiProvider.CLAUDE)
            ),
            connectionTest = ConnectionTestState.Success(ApiProvider.CLAUDE)
        )
    }

    /** 用途ごとに違う AI: タグ付けは端末内、要約は Gemini、チャットは Claude Haiku */
    @Test
    fun mixedTasks() = screenshot.captureLightDark("ai_settings_mixed_tasks") {
        Screen(
            AiSettingsUiState(
                tasks = mapOf(
                    AiTask.TAGGING to AiTaskSetting(LlmBackend.LOCAL),
                    AiTask.SUMMARY to AiTaskSetting(LlmBackend.API, ApiProvider.GEMINI),
                    AiTask.CHAT to AiTaskSetting(
                        LlmBackend.API,
                        ApiProvider.CLAUDE,
                        mapOf(ApiProvider.CLAUDE to "claude-haiku-4-5")
                    )
                ),
                localModels = InstalledModels(listOf(model)),
                keyProvider = ApiProvider.GEMINI,
                configuredProviders = setOf(ApiProvider.CLAUDE, ApiProvider.GEMINI)
            )
        )
    }

    /** クラウド API(Gemini)・ファイルの送信をオフにした状態 */
    @Test
    fun apiSendFilesOff() = screenshot.captureLightDark("ai_settings_api_send_files_off") {
        Screen(
            AiSettingsUiState(
                tasks = aiTasks(LlmBackend.API, ApiProvider.GEMINI),
                keyProvider = ApiProvider.GEMINI,
                configuredProviders = setOf(ApiProvider.GEMINI),
                sendFilesToCloud = false
            )
        )
    }

    /** API キーの削除の確認 */
    @Test
    fun clearKeyDialog() = screenshot.captureDialogLightDark("ai_settings_dialog_clear_key") {
        ClearApiKeyDialog(provider = ApiProvider.CLAUDE, onConfirm = {}, onDismiss = {})
    }

    /** クラウド API・キー未設定・接続失敗、モデル取り込み失敗 */
    @Test
    fun apiNeedsKey() = screenshot.captureLightDark("ai_settings_api_needs_key") {
        Screen(
            AiSettingsUiState(
                tasks = aiTasks(LlmBackend.API, ApiProvider.GEMINI),
                importState = ModelImportState.Failed(ModelImportError.UnsupportedFormat("model.bin")),
                keyProvider = ApiProvider.GEMINI,
                configuredProviders = setOf(ApiProvider.CLAUDE)
            ),
            connectionTest = ConnectionTestState.Failure("API キーが正しくありません。キーを確認してください")
        )
    }
}

package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.ModelImportError
import com.unchunks.echomark.data.ai.model.ModelImportState
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.LlmBackend
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
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class AiSettingsScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val model = LocalModelInfo(
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
        Box(Modifier.height(2000.dp)) {
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
                backend = LlmBackend.LOCAL,
                localModel = model,
                importState = ModelImportState.Copying(copiedBytes = 230_000_000, totalBytes = 555_000_000)
            )
        )
    }

    /** クラウド API・キー設定済み・接続テスト成功 */
    @Test
    fun apiReady() = screenshot.captureLightDark("ai_settings_api_ready") {
        Screen(
            AiSettingsUiState(
                backend = LlmBackend.API,
                localModel = model,
                apiProvider = ApiProvider.CLAUDE,
                apiModels = ApiProvider.entries.associateWith { it.defaultModel },
                configuredProviders = setOf(ApiProvider.CLAUDE)
            ),
            connectionTest = ConnectionTestState.Success(ApiProvider.CLAUDE)
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
                backend = LlmBackend.API,
                importState = ModelImportState.Failed(ModelImportError.UnsupportedFormat("model.bin")),
                apiProvider = ApiProvider.GEMINI,
                apiModels = ApiProvider.entries.associateWith { it.defaultModel },
                configuredProviders = setOf(ApiProvider.CLAUDE)
            ),
            connectionTest = ConnectionTestState.Failure("API キーが正しくありません。キーを確認してください")
        )
    }
}

package com.unchunks.echomark.ui.settings.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.ondevice.EngineBackend
import com.unchunks.echomark.data.ai.model.InstalledModels
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.ModelBenchmarkResult
import com.unchunks.echomark.data.ai.model.ModelBenchmarkState
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.aiTasks
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用途(タグ付け・要約・チャット)ごとに実行場所・提供元・モデルを選ぶ。 */
@RunWith(AndroidJUnit4::class)
class AiSettingsTaskSettingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val backends = mutableListOf<Pair<AiTask, LlmBackend>>()
    private val providers = mutableListOf<Pair<AiTask, ApiProvider>>()
    private val models = mutableListOf<Triple<AiTask, ApiProvider, String>>()
    private val localModels = mutableListOf<Pair<AiTask, String?>>()
    private val benchmarked = mutableListOf<LocalModelInfo>()

    private fun setContent(state: AiSettingsUiState) {
        composeRule.setContent {
            EchoMarkTheme {
                AiSettingsContent(
                    uiState = state,
                    connectionTest = ConnectionTestState.Idle,
                    actions = AiSettingsActions(
                        onSelectBackend = { task, backend -> backends += task to backend },
                        onSelectProvider = { task, provider -> providers += task to provider },
                        onSetModel = { task, provider, model -> models += Triple(task, provider, model) },
                        onSelectLocalModel = { task, modelId -> localModels += task to modelId }
                    )
                )
            }
        }
    }

    @Test
    fun 用途ごとの実行場所の切り替えをその用途として伝える() {
        // 実行場所の切り替えはクラウドを有効にしているときだけ出す(CLOUD_AI_ENABLED)
        Assume.assumeTrue(CLOUD_AI_ENABLED)
        setContent(AiSettingsUiState(tasks = aiTasks(LlmBackend.LOCAL)))

        // 「クラウド API」のボタンはタグ付け・要約・チャットの順に並ぶ
        composeRule.onAllNodesWithText("クラウド API")[1].performScrollTo().performClick()

        assertEquals(listOf(AiTask.SUMMARY to LlmBackend.API), backends)
    }

    @Test
    fun クラウドAPIの用途だけ提供元とモデルを選べる() {
        val chat = AiTaskSetting(LlmBackend.API, ApiProvider.CLAUDE)
        setContent(
            AiSettingsUiState(
                tasks = aiTasks(LlmBackend.LOCAL) + (AiTask.CHAT to chat),
                configuredProviders = setOf(ApiProvider.CLAUDE)
            )
        )

        // 提供元の選択肢は、クラウド API を選んだチャットの分だけ
        composeRule.onAllNodesWithText("Gemini").assertCountEquals(1)
        composeRule.onNodeWithText("Gemini").performScrollTo().performClick()
        composeRule.onNodeWithText("Claude Haiku 4.5(高速・低コスト)").performScrollTo().performClick()

        assertEquals(listOf(AiTask.CHAT to ApiProvider.GEMINI), providers)
        assertEquals(listOf(Triple(AiTask.CHAT, ApiProvider.CLAUDE, "claude-haiku-4-5")), models)
    }

    @Test
    fun 端末内モデルを2つ以上取り込んだら用途ごとに選べる() {
        val gemma = LocalModelInfo("gemma.litertlm", "gemma.litertlm", 1, 0)
        val qwen = LocalModelInfo("qwen.litertlm", "qwen.litertlm", 1, 0)
        setContent(
            AiSettingsUiState(tasks = aiTasks(LlmBackend.LOCAL), localModels = InstalledModels(listOf(gemma, qwen), gemma.id))
        )

        // 「既定」の選択肢はタグ付け・要約・チャットの順に並ぶ
        composeRule.onAllNodesWithText("既定(gemma)").assertCountEquals(AiTask.entries.size)
        composeRule.onAllNodesWithText("qwen")[2].performScrollTo().performClick()
        composeRule.onAllNodesWithText("既定(gemma)")[0].performScrollTo().performClick()

        assertEquals(listOf(AiTask.CHAT to qwen.id, AiTask.TAGGING to null), localModels)
    }

    @Test
    fun 測るボタンでそのモデルを測り_測定中は押せない() {
        val gemma = LocalModelInfo("gemma.litertlm", "gemma.litertlm", 1, 0)
        val qwen = LocalModelInfo("qwen.litertlm", "qwen.litertlm", 1, 0)
        var running by mutableStateOf<ModelBenchmarkState>(ModelBenchmarkState.Idle)
        composeRule.setContent {
            EchoMarkTheme {
                AiSettingsContent(
                    uiState = AiSettingsUiState(
                        tasks = aiTasks(LlmBackend.LOCAL),
                        localModels = InstalledModels(listOf(gemma, qwen), gemma.id),
                        benchmarkState = running
                    ),
                    connectionTest = ConnectionTestState.Idle,
                    actions = AiSettingsActions(onBenchmarkModel = { benchmarked += it })
                )
            }
        }

        composeRule.onAllNodesWithText("この端末での速さを測る")[1].performScrollTo().performClick()
        assertEquals(listOf(qwen), benchmarked)

        running = ModelBenchmarkState.Running(qwen)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("測定中", substring = true).assertExists()
        composeRule.onAllNodesWithText("この端末での速さを測る").assertCountEquals(1)
        composeRule.onNodeWithText("この端末での速さを測る").assertIsNotEnabled()
    }

    @Test
    fun 測定の結果は演算装置と速さを1行で伝える() {
        val result = ModelBenchmarkResult(
            "gemma", EngineBackend.GPU, 0, 3.25, 4.0, 80.04, 12.56, 256, 64, peakMemoryBytes = null
        )

        val summary = benchmarkSummary(result)

        assertTrue(summary, summary.startsWith("GPU: 読み込み 3.3 秒・入力 80.0 トークン/秒・出力 12.6 トークン/秒("))
        assertFalse("測れなかったメモリは出さない", summary.contains("メモリ"))
        assertTrue(benchmarkSummary(result.copy(peakMemoryBytes = 1_048_576)).contains("メモリ +"))
    }

    @Test
    fun 端末内モデルが1つなら用途ごとの選択を出さない() {
        val gemma = LocalModelInfo("gemma.litertlm", "gemma.litertlm", 1, 0)
        setContent(AiSettingsUiState(tasks = aiTasks(LlmBackend.LOCAL), localModels = InstalledModels(listOf(gemma), gemma.id)))

        composeRule.onAllNodesWithText("既定(gemma)").assertCountEquals(0)
    }

    @Test
    fun 準備ができていない用途を伝える() {
        val summary = AiTaskSetting(LlmBackend.API, ApiProvider.OPENAI)
        setContent(AiSettingsUiState(tasks = aiTasks(LlmBackend.LOCAL) + (AiTask.SUMMARY to summary)))

        composeRule.onNodeWithText("設定が必要な用途があります").assertExists()
        composeRule.onNodeWithText("要約: OpenAI の API キーを保存すると使えます").assertExists()
        composeRule.onNodeWithText("タグ付け: モデルファイルを取り込むと使えます").assertExists()
    }
}

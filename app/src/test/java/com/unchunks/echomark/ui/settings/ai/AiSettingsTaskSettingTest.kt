package com.unchunks.echomark.ui.settings.ai

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.aiTasks
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
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

    private fun setContent(state: AiSettingsUiState) {
        composeRule.setContent {
            EchoMarkTheme {
                AiSettingsContent(
                    uiState = state,
                    connectionTest = ConnectionTestState.Idle,
                    actions = AiSettingsActions(
                        onSelectBackend = { task, backend -> backends += task to backend },
                        onSelectProvider = { task, provider -> providers += task to provider },
                        onSetModel = { task, provider, model -> models += Triple(task, provider, model) }
                    )
                )
            }
        }
    }

    @Test
    fun 用途ごとの実行場所の切り替えをその用途として伝える() {
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
    fun 準備ができていない用途を伝える() {
        val summary = AiTaskSetting(LlmBackend.API, ApiProvider.OPENAI)
        setContent(AiSettingsUiState(tasks = aiTasks(LlmBackend.LOCAL) + (AiTask.SUMMARY to summary)))

        composeRule.onNodeWithText("設定が必要な用途があります").assertExists()
        composeRule.onNodeWithText("要約: OpenAI の API キーを保存すると使えます").assertExists()
        composeRule.onNodeWithText("タグ付け: モデルファイルを取り込むと使えます").assertExists()
    }
}

package com.unchunks.echomark.ui.settings.ai

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED
import com.unchunks.echomark.domain.repository.EmbeddingSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 「検索用の埋め込みを作る場所」: 端末内・クラウド API と提供元を選び、キーが無ければ知らせる。 */
@RunWith(AndroidJUnit4::class)
class AiSettingsEmbeddingLocationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val changes = mutableListOf<EmbeddingSetting>()

    @Before
    fun setUp() {
        // 作る場所の切り替えはクラウドを有効にしているときだけ出す(CLOUD_AI_ENABLED)
        Assume.assumeTrue(CLOUD_AI_ENABLED)
    }

    private fun setContent(setting: EmbeddingSetting, configured: Set<ApiProvider> = emptySet()) {
        composeRule.setContent {
            EchoMarkTheme {
                AiSettingsContent(
                    uiState = AiSettingsUiState(embeddingSetting = setting, configuredProviders = configured),
                    connectionTest = ConnectionTestState.Idle,
                    actions = AiSettingsActions(onSetEmbeddingSetting = { changes += it })
                )
            }
        }
    }

    @Test
    fun 端末内なら埋め込みモデルの欄を出し_クラウドAPIを選ぶと伝える() {
        setContent(EmbeddingSetting())

        composeRule.onNodeWithText("埋め込みモデルを取り込む").performScrollTo()
        composeRule.onNodeWithText(TITLE).performScrollTo()
        // 用途ごとの AI のカードにも「クラウド API」があるため、埋め込みのカードの中の最後のものを押す
        composeRule.onAllNodesWithText("クラウド API").onLast().performScrollTo().performClick()

        assertEquals(LlmBackend.API, changes.single().backend)
    }

    @Test
    fun クラウドAPIなら提供元を選べ_キーが無ければ知らせ_端末内のモデルの欄は出さない() {
        setContent(EmbeddingSetting(LlmBackend.API, ApiProvider.GEMINI), configured = setOf(ApiProvider.CLAUDE))

        composeRule.onNodeWithText("Gemini の API キーが未設定です", substring = true).performScrollTo()
        composeRule.onNodeWithText("埋め込みモデルを取り込む").assertDoesNotExist()
        // 下の「クラウド API のキー」にも提供元のチップがあるため、先に出る埋め込みのカードのものを押す
        composeRule.onAllNodesWithText("OpenAI").onFirst().performScrollTo().performClick()

        assertEquals(EmbeddingSetting(LlmBackend.API, ApiProvider.OPENAI), changes.single())
    }

    @Test
    fun キーがあれば知らせない() {
        setContent(EmbeddingSetting(LlmBackend.API, ApiProvider.OPENAI), configured = setOf(ApiProvider.OPENAI))

        composeRule.onNodeWithText("API キーが未設定です", substring = true).assertDoesNotExist()
    }

    @Test
    fun 説明は送るものと送り先_作り直しを伝える() {
        val notice = embeddingCloudNotice(ApiProvider.OPENAI)
        assertTrue(notice, notice.contains("text-embedding-3-small"))
        assertTrue(notice, notice.contains("OpenAI に送ります"))
        assertTrue(notice, notice.contains("作り直します"))
        assertFalse(notice, notice.contains("Claude (Anthropic) に送ります"))
    }

    private companion object {
        const val TITLE = "検索用の埋め込みを作る場所"
    }
}

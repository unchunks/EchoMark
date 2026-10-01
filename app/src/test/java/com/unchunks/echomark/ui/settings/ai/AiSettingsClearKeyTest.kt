package com.unchunks.echomark.ui.settings.ai

import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** API キーの「削除」は確認してから行う(押しただけでは消さない)。 */
@RunWith(AndroidJUnit4::class)
class AiSettingsClearKeyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val cleared = mutableListOf<ApiProvider>()

    private fun setContent() {
        composeRule.setContent {
            EchoMarkTheme {
                AiSettingsContent(
                    uiState = AiSettingsUiState(
                        backend = LlmBackend.API,
                        apiProvider = ApiProvider.CLAUDE,
                        configuredProviders = setOf(ApiProvider.CLAUDE)
                    ),
                    connectionTest = ConnectionTestState.Idle,
                    actions = AiSettingsActions(onClearKey = { cleared += it })
                )
            }
        }
        composeRule.onNodeWithText("削除").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun 削除を押すと確認を出し_まだ消さない() {
        setContent()

        composeRule.onNodeWithText("Claude の API キーを削除しますか?", substring = true).assertExists()
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun 確認で削除を選ぶと消す() {
        setContent()

        composeRule.onAllNodesWithText("削除").filter(hasAnyAncestor(isDialog())).onFirst().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(ApiProvider.CLAUDE), cleared)
    }

    @Test
    fun キャンセルすると消さない() {
        setContent()

        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.waitForIdle()

        assertTrue(cleared.isEmpty())
        composeRule.onNodeWithText("API キーを削除しますか?", substring = true).assertDoesNotExist()
    }
}

package com.unchunks.echomark.ui.settings.ai

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 「ファイルもクラウドで解析」のスイッチ: クラウド API のときだけ出し、切り替えを伝える。 */
@RunWith(AndroidJUnit4::class)
class AiSettingsSendFilesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val changes = mutableListOf<Boolean>()

    private fun setContent(backend: LlmBackend, sendFiles: Boolean) {
        composeRule.setContent {
            EchoMarkTheme {
                AiSettingsContent(
                    uiState = AiSettingsUiState(
                        backend = backend,
                        apiProvider = ApiProvider.CLAUDE,
                        configuredProviders = setOf(ApiProvider.CLAUDE),
                        sendFilesToCloud = sendFiles
                    ),
                    connectionTest = ConnectionTestState.Idle,
                    actions = AiSettingsActions(onSetSendFilesToCloud = { changes += it })
                )
            }
        }
    }

    @Test
    fun クラウドAPIのときスイッチを出し_タップで切り替えを伝える() {
        setContent(LlmBackend.API, sendFiles = true)

        composeRule.onNodeWithText(SWITCH_TITLE).performScrollTo().assertIsOn().performClick()

        assertEquals(listOf(false), changes)
    }

    @Test
    fun オフの状態を表示する() {
        setContent(LlmBackend.API, sendFiles = false)

        composeRule.onNodeWithText(SWITCH_TITLE).performScrollTo().assertIsOff()
    }

    @Test
    fun 端末内AIのときは出さない() {
        setContent(LlmBackend.LOCAL, sendFiles = true)

        composeRule.onNodeWithText(SWITCH_TITLE).assertDoesNotExist()
    }

    @Test
    fun 説明は送れる種類と料金_オフのときの動きを伝える() {
        val claude = sendFilesNotice(ApiProvider.CLAUDE, enabled = true)
        assertTrue(claude, claude.contains("画像と PDF"))
        assertTrue(claude, claude.contains("料金"))
        val gemini = sendFilesNotice(ApiProvider.GEMINI, enabled = true)
        assertTrue(gemini, gemini.contains("音声・動画を送れます"))
        val off = sendFilesNotice(ApiProvider.GEMINI, enabled = false)
        assertTrue(off, off.contains("読み取った文字だけ"))
        assertFalse(off, off.contains("料金"))
    }

    private companion object {
        const val SWITCH_TITLE = "ファイルもクラウドで解析"
    }
}

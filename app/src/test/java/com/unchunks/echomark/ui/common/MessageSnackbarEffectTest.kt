package com.unchunks.echomark.ui.common

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 状態で持つ Snackbar の文言を一度きりで出すこと(表示中に画面を離れて戻っても出し直さない)。 */
@RunWith(AndroidJUnit4::class)
class MessageSnackbarEffectTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val message = MutableStateFlow<String?>(null)
    private var shownCount = 0
    private var onScreen by mutableStateOf(true)

    private fun setContent() {
        composeRule.setContent {
            EchoMarkTheme {
                // 画面を離れると、この部分ごとコンポジションから外れる(NavHost で別の画面へ移ったときと同じ)
                if (onScreen) {
                    val hostState = remember { SnackbarHostState() }
                    MessageSnackbarEffect(message, hostState, onShown = {
                        shownCount++
                        message.value = null
                    })
                    SnackbarHost(hostState)
                }
            }
        }
    }

    @Test
    fun 表示する前に消費済みにして文言を出す() {
        setContent()

        composeRule.runOnIdle { message.value = "書き出しました" }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("書き出しました").assertExists()
        assertNull(message.value)
        assertEquals(1, shownCount)
    }

    @Test
    fun 表示中に画面を離れて戻っても同じ知らせを出し直さない() {
        setContent()
        composeRule.runOnIdle { message.value = "書き出しました" }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("書き出しました").assertExists()

        composeRule.runOnIdle { onScreen = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { onScreen = true }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("書き出しました").assertDoesNotExist()
        assertEquals(1, shownCount)
    }
}

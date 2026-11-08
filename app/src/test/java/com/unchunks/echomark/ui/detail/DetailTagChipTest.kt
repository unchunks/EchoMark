package com.unchunks.echomark.ui.detail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 詳細画面のタグは × でだけ外れ、チップ本体に触れても外れないこと。 */
@RunWith(AndroidJUnit4::class)
// 文字の幅を実際どおりに測る(既定の描画モードでは文字幅がほぼ 0 になり、本体を押したつもりが × の近くを押してしまう)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailTagChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val removed = mutableListOf<String>()

    private fun setContent() {
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkDetailContent(
                    uiState = BookmarkDetailUiState(isLoading = false, bookmark = PreviewSamples.urlBookmark),
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkDetailCallbacks(onRemoveTag = { removed += it }),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }
    }

    @Test
    fun チップ本体を押してもタグは外れない() {
        setContent()

        composeRule.onNodeWithText("#Android").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(removed.isEmpty())
    }

    @Test
    fun バツを押すとそのタグを外す() {
        setContent()

        composeRule.onNodeWithContentDescription("タグ「Android」を外す").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("Android"), removed)
    }
}

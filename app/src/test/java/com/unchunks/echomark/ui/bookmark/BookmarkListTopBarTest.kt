package com.unchunks.echomark.ui.bookmark

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 一覧をスクロールしてトップバーが隠れたあと、空状態に切り替わってもトップバーが戻ること。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class BookmarkListTopBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun スクロールで隠れたトップバーが空状態への切り替えで戻る() {
        val many = (1L..30L).map { PreviewSamples.urlBookmark.copy(id = it, isFavorite = false) }
        val state = mutableStateOf(BookmarkListUiState(bookmarks = many, totalCount = many.size))
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = state.value,
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }
        // 縦に並ぶカードの一覧(横スクロールの絞り込みチップではない方)をスクロールする
        composeRule.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ブックマーク").assertIsNotDisplayedOrMissing()

        // お気に入りで絞り込んだら 0 件だった(空状態はスクロールしない)
        composeRule.runOnIdle {
            state.value = BookmarkListUiState(filter = BookmarkFilter.FAVORITES, totalCount = many.size)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ブックマーク").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotDisplayedOrMissing() {
        if (fetchSemanticsNodes().isNotEmpty()) assertIsNotDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodes() =
        runCatching { listOf(fetchSemanticsNode()) }.getOrDefault(emptyList())
}

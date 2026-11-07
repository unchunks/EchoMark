package com.unchunks.echomark.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.bookmark.BookmarkListCallbacks
import com.unchunks.echomark.ui.bookmark.BookmarkListContent
import com.unchunks.echomark.ui.bookmark.BookmarkListUiState
import com.unchunks.echomark.ui.chat.ConversationListContent
import com.unchunks.echomark.ui.chat.ConversationListUiState
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 横向き(高さ 360dp)で空状態が画面に収まらなくても、次の行動のボタンまでスクロールで届くこと。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w900dp-h400dp-xhdpi")
class EmptyStateScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setLandscapeContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            EchoMarkTheme {
                Box(Modifier.size(800.dp, 360.dp)) { content() }
            }
        }
    }

    @Test
    fun 一覧の空状態のボタンまでスクロールできる() {
        setLandscapeContent {
            BookmarkListContent(
                uiState = BookmarkListUiState(totalCount = 0),
                snackbarHostState = SnackbarHostState(),
                callbacks = BookmarkListCallbacks(onAddClick = {})
            )
        }

        composeRule.onNodeWithText("URL を追加").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 会話一覧の空状態のボタンまでスクロールできる() {
        setLandscapeContent {
            ConversationListContent(
                uiState = ConversationListUiState(isLoading = false),
                nowMillis = 0L,
                onOpenConversation = {},
                onNewConversation = {},
                onOpenAiSettings = {},
                onRename = { _, _ -> },
                onDelete = {}
            )
        }

        composeRule.onNodeWithText("チャットを始める").performScrollTo().assertIsDisplayed()
    }
}

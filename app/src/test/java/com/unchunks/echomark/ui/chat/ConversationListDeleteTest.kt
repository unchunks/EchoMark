package com.unchunks.echomark.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 会話一覧の削除はメニューからだけ行い、横にスワイプしても消えないこと。 */
@RunWith(AndroidJUnit4::class)
class ConversationListDeleteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val target = ConversationPreview(
        conversation = Conversation(id = 1, title = "消して戻す会話", createdAt = 0, updatedAt = 0),
        lastMessage = "最後のメッセージ",
        lastMessageRole = ChatRole.ASSISTANT
    )

    // 一覧が空になると LazyColumn ごと破棄されるため、ほかの会話も並べておく(実際の利用に近い状態)
    private val other = target.copy(conversation = target.conversation.copy(id = 2, title = "残る会話"))

    private val conversations = mutableStateOf(listOf(target, other))
    private val deleted = mutableListOf<ConversationPreview>()

    private fun setContent() {
        composeRule.setContent {
            EchoMarkTheme {
                ConversationListContent(
                    uiState = ConversationListUiState(isLoading = false, conversations = conversations.value),
                    nowMillis = 0L,
                    onOpenConversation = {},
                    onNewConversation = {},
                    onOpenAiSettings = {},
                    onRename = { _, _ -> },
                    onDelete = {
                        deleted += it
                        conversations.value = conversations.value - it
                    }
                )
            }
        }
    }

    @Test
    fun 横にスワイプしても削除されない() {
        setContent()

        composeRule.onNodeWithText(target.conversation.title).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText(target.conversation.title).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(emptyList<ConversationPreview>(), deleted)
        composeRule.onNodeWithText(target.conversation.title).assertIsDisplayed()
    }

    @Test
    fun メニューから削除して元に戻すと行が表示される() {
        setContent()

        composeRule.onNodeWithContentDescription("「${target.conversation.title}」の操作").performClick()
        composeRule.onNodeWithText("削除").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(target), deleted)
        composeRule.onNodeWithText(target.conversation.title).assertDoesNotExist()

        // 「元に戻す」で同じ ID の会話が戻ってくる
        composeRule.runOnIdle { conversations.value = listOf(target, other) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(target.conversation.title).assertIsDisplayed()
    }
}

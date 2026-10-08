package com.unchunks.echomark.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 会話一覧のスワイプ削除を「元に戻す」で取り消したとき、行がスワイプ済み(画面外)のまま戻らないこと。 */
@RunWith(AndroidJUnit4::class)
class ConversationListSwipeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val target = ConversationPreview(
        conversation = Conversation(id = 1, title = "消して戻す会話", createdAt = 0, updatedAt = 0),
        lastMessage = "最後のメッセージ",
        lastMessageRole = ChatRole.ASSISTANT
    )

    // 一覧が空になると LazyColumn ごと破棄されて保存状態も消えるため、ほかの会話も並べておく(実際の利用に近い状態)
    private val other = target.copy(conversation = target.conversation.copy(id = 2, title = "残る会話"))

    @Test
    fun スワイプで削除して元に戻すと行が元の位置に表示される() {
        val conversations = mutableStateOf(listOf(target, other))
        val deleted = mutableListOf<ConversationPreview>()
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

        composeRule.onNodeWithText(target.conversation.title).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(listOf(target), deleted)
        composeRule.onNodeWithText(target.conversation.title).assertDoesNotExist()

        // 「元に戻す」で同じ ID の会話が戻ってくる
        composeRule.runOnIdle { conversations.value = listOf(target, other) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(target.conversation.title).assertIsDisplayed()
    }
}

package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.MODEL_NOT_AVAILABLE_USER_MESSAGE
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.ui.chat.ChatContent
import com.unchunks.echomark.ui.chat.ChatError
import com.unchunks.echomark.ui.chat.ChatSuggestions
import com.unchunks.echomark.ui.chat.ChatUiState
import com.unchunks.echomark.ui.chat.ConversationListContent
import com.unchunks.echomark.ui.chat.ConversationListUiState
import com.unchunks.echomark.ui.components.PreviewSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** チャット画面・会話一覧の主要な状態の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 画面全体(400dp 幅 + 撮影用の余白 16dp×2)が収まるウィンドウにする
@Config(qualifiers = "w432dp-h1000dp-xhdpi")
class ChatScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW
    private val minute = 60_000L

    private val question = ChatMessage(
        id = 1, conversationId = 1, role = ChatRole.USER,
        content = "Compose のパフォーマンスについて保存したものの要点をまとめて", createdAt = now - 10 * minute
    )

    private val answer = ChatMessage(
        id = 2, conversationId = 1, role = ChatRole.ASSISTANT,
        content = """
            ## 要点
            保存した記事から、次の3つが大事だと分かります [1]。

            1. **再コンポーズを減らす**: 状態を安定させ、`remember` と `derivedStateOf` を使い分ける
            2. Lazy リストには `key` を指定する [1]
            3. 情報は*編集*して自分の知識にする [2, 3]

            ```kotlin
            items(list, key = { it.id }) { Row(it) }
            ```
        """.trimIndent(),
        // 3件目(id=99)は削除済みの想定。番号は残るが押せず、カードにも出ない
        referencedBookmarkIds = listOf(1L, 3L, 99L),
        createdAt = now - 9 * minute
    )

    private val referenced = mapOf(1L to PreviewSamples.urlBookmark, 3L to PreviewSamples.textBookmark)

    private val conversationState = ChatUiState(
        title = "Compose のパフォーマンスについて保存…",
        hasConversation = true,
        messages = listOf(question, answer),
        referencedBookmarks = referenced
    )

    /** 画面全体を、一般的なスマートフォンの大きさで撮る。 */
    private fun captureScreen(name: String, content: @Composable () -> Unit) =
        screenshot.captureLightDark(name, widthDp = SCREEN_WIDTH_DP + 32) {
            Box(Modifier.height(SCREEN_HEIGHT_DP.dp)) { content() }
        }

    @Composable
    private fun Chat(state: ChatUiState, input: String = "") {
        ChatContent(
            uiState = state,
            input = input,
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onBack = {},
            onOpenBookmark = {},
            onOpenAiSettings = {},
            onRename = {},
            onDelete = {},
            onCopy = {}
        )
    }

    @Test
    fun newConversation() = captureScreen("chat_new") {
        Chat(
            ChatUiState(
                suggestions = ChatSuggestions.forLibrary(PreviewSamples.all)
            )
        )
    }

    @Test
    fun aboutBookmark() = captureScreen("chat_about_bookmark") {
        Chat(
            ChatUiState(
                aboutBookmark = PreviewSamples.urlBookmark,
                suggestions = ChatSuggestions.forBookmark
            ),
            input = "この記事で一番大事な"
        )
    }

    /** 会話一覧から開き直した「このブックマークについて質問」の会話(対象は会話に保存されている)。 */
    @Test
    fun aboutBookmarkResumed() = captureScreen("chat_about_bookmark_resumed") {
        Chat(
            conversationState.copy(
                title = "Jetpack Compose のパフォーマンスについて",
                aboutBookmark = PreviewSamples.urlBookmark,
                suggestions = ChatSuggestions.forBookmark
            )
        )
    }

    @Test
    fun conversation() = captureScreen("chat_conversation") {
        Chat(conversationState)
    }

    @Test
    fun streaming() = captureScreen("chat_streaming") {
        Chat(
            conversationState.copy(
                messages = listOf(question),
                isSending = true,
                streamingText = "## 要点\n保存した記事から、次の3つが大事だと分かります [1]。\n\n1. **再コンポーズを減らす**: 状態を",
                pendingReferenceCount = 2
            ),
            input = "次は"
        )
    }

    @Test
    fun searching() = captureScreen("chat_searching") {
        Chat(
            conversationState.copy(
                messages = listOf(question),
                isSending = true,
                streamingText = "",
                pendingReferenceCount = null
            )
        )
    }

    @Test
    fun errorWithRetry() = captureScreen("chat_error") {
        Chat(
            conversationState.copy(
                messages = listOf(question),
                error = ChatError(
                    message = LlmException.Network().userMessage,
                    failedMessage = question.content
                )
            )
        )
    }

    @Test
    fun aiNotConfigured() = captureScreen("chat_ai_not_ready") {
        Chat(
            conversationState.copy(
                messages = listOf(question),
                aiSetup = AiSetupState.LOCAL_MODEL_MISSING,
                error = ChatError(
                    message = MODEL_NOT_AVAILABLE_USER_MESSAGE,
                    failedMessage = question.content,
                    needsAiSettings = true
                )
            )
        )
    }

    private val previews = listOf(
        ConversationPreview(
            conversation = Conversation(
                id = 1, title = "Compose のパフォーマンスについて保存…",
                createdAt = now - 2 * 60 * minute, updatedAt = now - 9 * minute
            ),
            lastMessage = answer.content,
            lastMessageRole = ChatRole.ASSISTANT
        ),
        ConversationPreview(
            conversation = Conversation(
                id = 2, title = "読書メモ: 『知の編集術』について",
                isTitleManuallySet = true,
                createdAt = now - 3 * 24 * 60 * minute, updatedAt = now - 2 * 24 * 60 * minute
            ),
            lastMessage = "情報を編集するとはどういうこと？",
            lastMessageRole = ChatRole.USER
        ),
        ConversationPreview(
            conversation = Conversation(
                id = 4, title = "あとで試したいこと",
                isTitleManuallySet = true,
                createdAt = now - 4 * 24 * 60 * minute, updatedAt = now - 3 * 24 * 60 * minute,
                aboutBookmarkId = PreviewSamples.urlBookmark.id
            ),
            lastMessage = "記事の手順を、今のプロジェクトに当てはめると…",
            lastMessageRole = ChatRole.ASSISTANT,
            aboutBookmarkTitle = PreviewSamples.urlBookmark.title
        ),
        ConversationPreview(
            conversation = Conversation(
                id = 3, title = "新しいチャット",
                createdAt = now - 40 * 24 * 60 * minute, updatedAt = now - 40 * 24 * 60 * minute
            )
        )
    )

    @Composable
    private fun ConversationList(state: ConversationListUiState) {
        ConversationListContent(
            uiState = state,
            nowMillis = now,
            onOpenConversation = {},
            onNewConversation = {},
            onOpenAiSettings = {},
            onRename = { _, _ -> },
            onDelete = {}
        )
    }

    @Test
    fun conversationList() = captureScreen("conversation_list") {
        ConversationList(ConversationListUiState(isLoading = false, conversations = previews))
    }

    @Test
    fun conversationListEmpty() = captureScreen("conversation_list_empty") {
        ConversationList(ConversationListUiState(isLoading = false))
    }

    @Test
    fun conversationListAiNotReady() = captureScreen("conversation_list_ai_not_ready") {
        ConversationList(
            ConversationListUiState(
                isLoading = false,
                conversations = previews,
                aiSetup = AiSetupState.API_KEY_MISSING
            )
        )
    }

    private companion object {
        const val SCREEN_WIDTH_DP = 400
        const val SCREEN_HEIGHT_DP = 820
    }
}

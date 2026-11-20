package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.websearch.WebSearchAvailability
import com.unchunks.echomark.domain.websearch.WebSource
import com.unchunks.echomark.ui.chat.AnswerClaim
import com.unchunks.echomark.ui.chat.AnswerReview
import com.unchunks.echomark.ui.chat.ChatContent
import com.unchunks.echomark.ui.chat.ChatUiState
import com.unchunks.echomark.ui.chat.ChatWebSearchState
import com.unchunks.echomark.ui.chat.CitedSource
import com.unchunks.echomark.ui.chat.CitedWebSource
import com.unchunks.echomark.ui.chat.ClaimStatus
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.settings.ai.WebSearchKeySection
import com.unchunks.echomark.ui.settings.ai.WebSearchKeyTest
import com.unchunks.echomark.ui.settings.ai.WebSearchSettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** チャットの Web 検索(入力欄のボタン・オンの帯・Web の引用元)と、AI 設定のキーの見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w432dp-h1000dp-xhdpi")
class WebSearchScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW
    private val minute = 60_000L

    private val question = ChatMessage(
        id = 1, conversationId = 1, role = ChatRole.USER,
        content = "Kotlin 2.3 で変わったことを、保存した記事と合わせて教えて", createdAt = now - 10 * minute
    )

    private val page = WebSource(
        number = 2,
        url = "https://kotlinlang.org/docs/whatsnew23.html",
        title = "What's new in Kotlin 2.3",
        domain = "kotlinlang.org",
        excerpt = "Kotlin 2.3 では、K2 コンパイラーの改善と新しい標準ライブラリの API が追加されました。"
    )
    private val snippetOnly = WebSource(3, "https://example.com/news/kotlin", "example.com", "example.com", "")

    private val answer = ChatMessage(
        id = 2, conversationId = 1, role = ChatRole.ASSISTANT,
        content = "保存した記事では、K2 コンパイラーで速くなったことが紹介されています [1]。" +
            "公式の解説によると、Kotlin 2.3 では K2 コンパイラーの改善と新しい標準ライブラリの API が追加されました [2]。",
        referencedBookmarkIds = listOf(1L),
        createdAt = now - 9 * minute,
        webSources = listOf(page, snippetOnly)
    )

    private val webOn = ChatWebSearchState(enabled = true, availability = WebSearchAvailability(keyConfigured = true))

    private val state = ChatUiState(
        title = "Kotlin 2.3 で変わったこと",
        hasConversation = true,
        messages = listOf(question, answer),
        referencedBookmarks = mapOf(1L to PreviewSamples.urlBookmark),
        answerReviews = mapOf(
            answer.id to AnswerReview(
                sources = listOf(CitedSource(1, 1L, null)),
                webSources = listOf(CitedWebSource(page, "Kotlin 2.3 では、K2 コンパイラーの改善と新しい標準ライブラリの API が追加されました。")),
                uncitedWeb = listOf(snippetOnly),
                claims = listOf(AnswerClaim("公式の解説によると…", listOf(2), ClaimStatus.SUPPORTED))
            )
        ),
        webSearch = webOn
    )

    private fun captureScreen(name: String, content: @Composable () -> Unit) =
        screenshot.captureLightDark(name, widthDp = 432) {
            Box(Modifier.height(900.dp)) { content() }
        }

    @Composable
    private fun Chat(state: ChatUiState) {
        ChatContent(
            uiState = state,
            input = "",
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
    fun answerWithWebSources() = captureScreen("chat_web_sources") {
        Chat(state.copy(webSearch = webOn.copy(savedUrls = setOf(page.url))))
    }

    @Test
    fun searchingWeb() = captureScreen("chat_web_searching") {
        Chat(state.copy(messages = listOf(question), isSending = true, streamingText = "", isSearchingWeb = true))
    }

    @Test
    fun offlineWithNotice() = captureScreen("chat_web_offline") {
        Chat(
            state.copy(
                webSearch = webOn.copy(
                    availability = WebSearchAvailability(keyConfigured = true, offline = true),
                    notice = "オフラインモードのため、Web は検索しませんでした"
                )
            )
        )
    }

    @Test
    fun webSearchOff() = captureScreen("chat_web_off") {
        Chat(state.copy(messages = emptyList(), hasConversation = false, webSearch = ChatWebSearchState()))
    }

    @Test
    fun keyPrompt() = captureScreen("chat_web_key_prompt") {
        Chat(state.copy(webSearch = ChatWebSearchState(showKeyPrompt = true)))
    }

    @Test
    fun keySettings() = screenshot.captureLightDark("ai_settings_web_search", widthDp = 400) {
        Surface {
            WebSearchKeySection(
                state = WebSearchSettingsUiState(keyConfigured = true, test = WebSearchKeyTest.Success),
                onSave = {},
                onClear = {},
                onTest = {}
            )
        }
    }

    @Test
    fun keySettingsFailure() = screenshot.captureLightDark("ai_settings_web_search_failure", widthDp = 400) {
        Surface {
            WebSearchKeySection(
                state = WebSearchSettingsUiState(keyConfigured = false, test = WebSearchKeyTest.Failure("Brave Search API のキーが使えませんでした。キーを確かめてください")),
                onSave = {},
                onClear = {},
                onTest = {}
            )
        }
    }
}

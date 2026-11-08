package com.unchunks.echomark.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.model.ChatAttachmentContext
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.repository.ChatAttachmentSource
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.echomark.domain.repository.SaveResult
import com.unchunks.echomark.domain.websearch.WebSearchAvailability
import com.unchunks.echomark.domain.websearch.WebSource
import com.unchunks.echomark.testing.FakeAiSetupRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeChatModelRepository
import com.unchunks.echomark.testing.FakeChatRepository
import com.unchunks.echomark.testing.FakeChatSpeechOutput
import com.unchunks.echomark.testing.FakeChatVoiceInput
import com.unchunks.echomark.testing.FakeWebSearchStatus
import com.unchunks.components.testing.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** チャットの Web 検索のオン・オフと、その知らせ。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelWebSearchTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeChatRepository()
    private val status = FakeWebSearchStatus(WebSearchAvailability(keyConfigured = true))

    private fun createViewModel(savedState: SavedStateHandle = SavedStateHandle()) = ChatViewModel(
        repository,
        FakeAiSetupRepository(),
        savedState,
        object : ChatAttachmentSource {
            override suspend fun load(uri: String): ChatAttachmentContext = ChatAttachmentContext("a.txt", "本文")
        },
        FakeChatModelRepository(),
        TestDispatcherProvider(Dispatchers.Unconfined),
        FakeAppSettingsRepository(),
        FakeChatVoiceInput(),
        FakeChatSpeechOutput(),
        status
    )

    @Test
    fun 新しい会話ではオフで始まり_オンにすると最初の送信で会話に保存して検索する() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.webSearch.enabled)

        viewModel.setWebSearchEnabled(true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.webSearch.enabled)
        assertTrue(viewModel.uiState.value.webSearch.active)

        viewModel.sendMessage("Kotlin の最新版は？")
        advanceUntilIdle()

        assertEquals(listOf(true), repository.createdWebSearch)
        assertEquals(listOf(true), repository.sentWebSearch)
    }

    @Test
    fun オフのままなら検索しない() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("質問")
        advanceUntilIdle()

        assertEquals(listOf(false), repository.createdWebSearch)
        assertEquals(listOf(false), repository.sentWebSearch)
    }

    @Test
    fun 既存の会話では会話に保存したオンオフを使い_切り替えは会話に書く() = runTest {
        repository.conversation.value = Conversation(id = 42, title = "会話", createdAt = 0, updatedAt = 0, webSearchEnabled = true)
        val viewModel = createViewModel(SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to 42L)))
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.webSearch.enabled)

        viewModel.setWebSearchEnabled(false)
        advanceUntilIdle()

        assertEquals(listOf(42L to false), repository.webSearchChanges)
        assertFalse(viewModel.uiState.value.webSearch.enabled)
    }

    @Test
    fun キーが無ければオンにせず設定への案内を出す() = runTest {
        status.availability.value = WebSearchAvailability(keyConfigured = false)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.setWebSearchEnabled(true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.webSearch.enabled)
        assertTrue(viewModel.uiState.value.webSearch.showKeyPrompt)
        viewModel.dismissWebSearchKeyPrompt()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.webSearch.showKeyPrompt)
    }

    @Test
    fun オフラインモードならオンにせず理由を出す() = runTest {
        status.availability.value = WebSearchAvailability(keyConfigured = true, offline = true)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.setWebSearchEnabled(true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.webSearch.enabled)
        assertTrue(viewModel.uiState.value.webSearch.notice!!.contains("オフラインモード"))
    }

    @Test
    fun オンのままキーを消したら_検索せずに送る() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.setWebSearchEnabled(true)
        advanceUntilIdle()
        status.availability.value = WebSearchAvailability(keyConfigured = false)

        viewModel.sendMessage("質問")
        advanceUntilIdle()

        assertEquals(listOf(false), repository.sentWebSearch)
        assertFalse(viewModel.uiState.value.webSearch.active)
    }

    @Test
    fun 検索できなかった理由を出し_次の送信で消す() = runTest {
        repository.eventsBeforeStart = listOf(ChatStreamEvent.SearchingWeb, ChatStreamEvent.WebSearchSkipped("上限に達しました"))
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.setWebSearchEnabled(true)
        advanceUntilIdle()

        viewModel.sendMessage("質問")
        advanceUntilIdle()
        assertEquals("上限に達しました", viewModel.uiState.value.webSearch.notice)
        assertFalse(viewModel.uiState.value.isSearchingWeb)

        repository.eventsBeforeStart = emptyList()
        viewModel.sendMessage("次の質問")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.webSearch.notice)
    }

    @Test
    fun Webのページをブックマークに保存し_保存した印を付ける() = runTest {
        repository.webSourceSaveResult = SaveResult(id = 9L, isDuplicate = true)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        val page = WebSource(2, "https://example.com/a", "題", "example.com", "抜粋")
        var result: SaveResult? = null

        viewModel.saveWebSource(page) { result = it }
        advanceUntilIdle()

        assertEquals(listOf(page), repository.savedWebSources)
        assertEquals(SaveResult(9L, isDuplicate = true), result)
        assertTrue("https://example.com/a" in viewModel.uiState.value.webSearch.savedUrls)
    }

    @Test
    fun 進み具合にWebの検索中とページの数を出す() {
        assertEquals("Web を検索しています…", progressLabel(null, isSearchingWeb = true))
        assertEquals("2件のブックマークと3件の Web ページをもとに回答を作成しています…", progressLabel(2, webCount = 3))
        assertEquals("3件の Web ページをもとに回答を作成しています…", progressLabel(0, webCount = 3))
        assertEquals("2件のブックマークをもとに回答を作成しています…", progressLabel(2, webCount = 0))
    }

    @Test
    fun 帯の説明はオンでも使えない理由を出す() {
        val on = ChatWebSearchState(enabled = true, availability = WebSearchAvailability(keyConfigured = true))
        assertTrue(webSearchStatusText(on).contains("Brave Search に送り"))
        assertTrue(webSearchStatusText(on.copy(availability = WebSearchAvailability(true, offline = true))).contains("オフラインモード"))
        assertTrue(webSearchStatusText(on.copy(availability = WebSearchAvailability(false))).contains("キーがありません"))
    }
}

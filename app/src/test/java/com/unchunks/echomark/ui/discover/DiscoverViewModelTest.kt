package com.unchunks.echomark.ui.discover

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.websearch.ResourceCandidate
import com.unchunks.components.websearch.ResourceSearchClient
import com.unchunks.components.websearch.ResourceSearcher
import com.unchunks.components.websearch.ResourceSource
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.discover.ResourceDiscovery
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.MODEL_NOT_AVAILABLE_USER_MESSAGE
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeAiSetupRepository
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val bookmarks = FakeBookmarkRepository()
    private var llmResponse = """{"picks": [{"n": 1, "reason": "入門向け"}]}"""
    private var llmFailure: Exception? = null

    private val llm = object : LlmProvider {
        override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis =
            error("not used")
        override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String =
            error("not used")
        override suspend fun complete(prompt: String): String {
            llmFailure?.let { throw it }
            return llmResponse
        }
    }

    private val articles = object : ResourceSearchClient {
        override val source = ResourceSource.ARTICLE
        override suspend fun search(query: String, limit: Int): List<ResourceCandidate> = listOf(
            ResourceCandidate("https://qiita.com/a", "Kotlin 入門", "基本", ResourceSource.ARTICLE),
            ResourceCandidate("https://zenn.dev/b", "Kotlin 応用", "応用", ResourceSource.ARTICLE)
        )
    }

    private fun createViewModel() = DiscoverViewModel(
        ResourceDiscovery(
            searcher = ResourceSearcher(listOf(articles)),
            llmProviderResolver = LlmProviderResolver(llm, { llm }, FakeAppSettingsRepository(LlmBackend.LOCAL), FakeApiKeyRepository()),
            bookmarkRepository = bookmarks
        ),
        FakeAiSetupRepository()
    )

    @Test
    fun 探すとAIのおすすめを選んだ状態で並べる() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.searchTopic("Kotlin")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.step)
        assertTrue(state.hasSearched)
        assertEquals(listOf("Kotlin 入門"), state.recommended.map { it.candidate.title })
        assertEquals(listOf("Kotlin 応用"), state.others.map { it.candidate.title })
        assertEquals(setOf("https://qiita.com/a"), state.selectedUrls)
    }

    @Test
    fun AIが使えないときは案内を出して検索の結果を並べる() = runTest {
        llmFailure = ModelNotAvailableException()
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.searchTopic("Kotlin")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.aiNotice!!.startsWith(MODEL_NOT_AVAILABLE_USER_MESSAGE))
        assertEquals(2, state.others.size)
        assertTrue(state.selectedUrls.isEmpty())
    }

    @Test
    fun 選んだ資料を追加すると保存済みにして知らせる() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.searchTopic("Kotlin")
        advanceUntilIdle()

        viewModel.toggleSelection("https://zenn.dev/b")
        viewModel.saveSelected()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("https://qiita.com/a", "https://zenn.dev/b"), bookmarks.savedUrls.map { it.first })
        assertTrue(state.resources.all { it.isSaved })
        assertTrue(state.selectedUrls.isEmpty())
        assertEquals("2件をブックマークに追加しました。AI が本文を読んで要約します", state.message)
        // 保存済みのものは選び直せない
        viewModel.toggleSelection("https://qiita.com/a")
        assertTrue(viewModel.uiState.value.selectedUrls.isEmpty())
    }

    @Test
    fun 検索先は1つも選ばない状態にはしない() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        listOf(ResourceSource.ARTICLE, ResourceSource.VIDEO, ResourceSource.ENCYCLOPEDIA).forEach { viewModel.toggleSource(it) }
        advanceUntilIdle()

        assertEquals(setOf(ResourceSource.ENCYCLOPEDIA), viewModel.uiState.value.sources)
    }

    @Test
    fun テーマが空なら探さない() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.search()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasSearched)
    }
}

package com.unchunks.echomark.ui.settings.ai

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.web.OfflineModeException
import com.unchunks.echomark.domain.websearch.WebSearchClient
import com.unchunks.echomark.domain.websearch.WebSearchException
import com.unchunks.echomark.domain.websearch.WebSearchResult
import com.unchunks.echomark.testing.FakeApiKeyRepository
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
class WebSearchSettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val keys = FakeApiKeyRepository()

    private class FakeClient : WebSearchClient {
        var failure: Exception? = null
        val testedKeys = mutableListOf<String?>()
        override suspend fun search(query: String, count: Int): List<WebSearchResult> = emptyList()
        override suspend fun testKey(apiKey: String?) {
            testedKeys += apiKey
            failure?.let { throw it }
        }
    }

    private val client = FakeClient()

    @Test
    fun キーを保存_削除すると状態に出る() = runTest {
        val viewModel = WebSearchSettingsViewModel(keys, client)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.saveKey("  brave-key  ")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.keyConfigured)
        assertEquals("brave-key", keys.webSearchKey.value)

        viewModel.clearKey()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.keyConfigured)
        assertNull(keys.webSearchKey.value)
    }

    @Test
    fun 空のキーは保存しない() = runTest {
        val viewModel = WebSearchSettingsViewModel(keys, client)

        viewModel.saveKey("   ")
        advanceUntilIdle()

        assertNull(keys.webSearchKey.value)
    }

    @Test
    fun 接続テストは入力中のキーを使い_空なら保存したキーを使う() = runTest {
        val viewModel = WebSearchSettingsViewModel(keys, client)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.testKey("typed")
        advanceUntilIdle()
        assertEquals(WebSearchKeyTest.Success, viewModel.uiState.value.test)

        viewModel.testKey("")
        advanceUntilIdle()
        assertEquals(listOf("typed", null), client.testedKeys)
    }

    @Test
    fun 接続テストの失敗は理由を出す() = runTest {
        val viewModel = WebSearchSettingsViewModel(keys, client)
        backgroundScope.launch { viewModel.uiState.collect {} }

        client.failure = WebSearchException.InvalidKey()
        viewModel.testKey("bad")
        advanceUntilIdle()
        assertEquals(WebSearchKeyTest.Failure(WebSearchException.InvalidKey().message!!), viewModel.uiState.value.test)

        client.failure = OfflineModeException("api.search.brave.com")
        viewModel.testKey("bad")
        advanceUntilIdle()
        assertEquals(WebSearchKeyTest.Failure("オフラインモードのため確かめられません"), viewModel.uiState.value.test)
    }
}

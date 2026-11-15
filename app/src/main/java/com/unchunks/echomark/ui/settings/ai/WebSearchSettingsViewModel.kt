package com.unchunks.echomark.ui.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.data.network.isOfflineModeBlock
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.websearch.WebSearchClient
import com.unchunks.echomark.domain.websearch.WebSearchException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Web 検索のキーを試した結果。 */
sealed interface WebSearchKeyTest {
    data object Idle : WebSearchKeyTest
    data object Running : WebSearchKeyTest
    data object Success : WebSearchKeyTest
    data class Failure(val message: String) : WebSearchKeyTest
}

/**
 * AI 設定の「Web 検索」の状態。
 * @property keyConfigured Brave Search API のキーを保存しているか
 */
data class WebSearchSettingsUiState(
    val keyConfigured: Boolean = false,
    val test: WebSearchKeyTest = WebSearchKeyTest.Idle
)

/** AI 設定の「Web 検索」。Brave Search API のキーの保存・削除・確認。キーそのものは画面に返さない。 */
@HiltViewModel
class WebSearchSettingsViewModel @Inject constructor(
    private val apiKeys: ApiKeyRepository,
    private val searchClient: WebSearchClient
) : ViewModel() {

    private val testFlow = MutableStateFlow<WebSearchKeyTest>(WebSearchKeyTest.Idle)

    val uiState: StateFlow<WebSearchSettingsUiState> =
        combine(apiKeys.webSearchKeyConfigured, testFlow, ::WebSearchSettingsUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebSearchSettingsUiState())

    fun saveKey(apiKey: String) {
        if (apiKey.isBlank()) return
        testFlow.value = WebSearchKeyTest.Idle
        viewModelScope.launch { apiKeys.setWebSearchKey(apiKey) }
    }

    fun clearKey() {
        testFlow.value = WebSearchKeyTest.Idle
        viewModelScope.launch { apiKeys.clearWebSearchKey() }
    }

    /** [typedKey](空なら保存したキー)で1回検索してみる。 */
    fun testKey(typedKey: String) {
        if (testFlow.value == WebSearchKeyTest.Running) return
        testFlow.value = WebSearchKeyTest.Running
        viewModelScope.launch {
            testFlow.value = try {
                searchClient.testKey(typedKey.ifBlank { null })
                WebSearchKeyTest.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Web 検索のキーを確かめられなかった: %s", e.javaClass.simpleName)
                WebSearchKeyTest.Failure(failureMessage(e))
            }
        }
    }

    private fun failureMessage(e: Exception): String = when {
        e.isOfflineModeBlock() -> "オフラインモードのため確かめられません"
        e is WebSearchException -> e.message ?: FAILED
        else -> FAILED
    }

    private companion object {
        const val FAILED = "Brave Search API に接続できませんでした"
    }
}

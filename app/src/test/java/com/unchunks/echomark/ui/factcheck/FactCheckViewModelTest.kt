package com.unchunks.echomark.ui.factcheck

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.web.OfflineModeException
import com.unchunks.echomark.domain.factcheck.CheckedClaim
import com.unchunks.echomark.domain.factcheck.ClaimVerdict
import com.unchunks.echomark.domain.factcheck.FactCheckProgress
import com.unchunks.echomark.domain.factcheck.FactCheckRepository
import com.unchunks.echomark.domain.factcheck.FactCheckResult
import com.unchunks.echomark.domain.factcheck.FactCheckTarget
import com.unchunks.echomark.domain.factcheck.FactCheckText
import com.unchunks.echomark.domain.factcheck.NothingToFactCheckException
import com.unchunks.echomark.domain.websearch.WebSearchAvailability
import com.unchunks.echomark.domain.websearch.WebSearchException
import com.unchunks.echomark.testing.FakeWebSearchStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
class FactCheckViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class FakeRepository : FactCheckRepository {
        val stored = MutableStateFlow<Map<FactCheckTarget, FactCheckResult>>(emptyMap())
        val checks = mutableListOf<Pair<FactCheckTarget, String>>()
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override fun observe(target: FactCheckTarget): Flow<FactCheckResult?> = stored.map { it[target] }

        override suspend fun check(target: FactCheckTarget, text: String, onProgress: (FactCheckProgress) -> Unit): FactCheckResult {
            checks += target to text
            onProgress(FactCheckProgress.Searching(1, 2))
            gate?.await()
            failure?.let { throw it }
            val result = FactCheckResult(target, FactCheckText.hash(text), listOf(CheckedClaim("主張", ClaimVerdict.SUPPORTED)), 5)
            stored.value = stored.value + (target to result)
            return result
        }
    }

    private val repository = FakeRepository()
    private val status = FakeWebSearchStatus(WebSearchAvailability(keyConfigured = true))
    private val target = FactCheckTarget.bookmark(3)

    @Test
    fun 開くだけでは検索せず_保存した結果を出す() = runTest {
        val saved = FactCheckResult(target, FactCheckText.hash("要約"), listOf(CheckedClaim("主張", ClaimVerdict.NOT_FOUND)), 1)
        repository.stored.value = mapOf(target to saved)
        val viewModel = FactCheckViewModel(repository, status)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.open(target, "要約")
        advanceUntilIdle()

        assertEquals(saved, viewModel.uiState.value.result)
        assertFalse(viewModel.uiState.value.isStale)
        assertTrue(repository.checks.isEmpty())
    }

    @Test
    fun 確かめると途中の段階を出し_終わると結果を出す() = runTest {
        repository.gate = CompletableDeferred()
        val viewModel = FactCheckViewModel(repository, status)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.open(target, "要約")

        viewModel.check()
        advanceUntilIdle()
        assertEquals(FactCheckProgress.Searching(1, 2), viewModel.uiState.value.progress)
        assertTrue(viewModel.uiState.value.isRunning)

        repository.gate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.progress)
        assertEquals(ClaimVerdict.SUPPORTED, viewModel.uiState.value.result!!.claims.single().verdict)
        assertEquals(listOf(target to "要約"), repository.checks)
    }

    @Test
    fun 要約が変わっていたら古い結果として出す() = runTest {
        repository.stored.value = mapOf(target to FactCheckResult(target, FactCheckText.hash("前の要約"), emptyList(), 1))
        val viewModel = FactCheckViewModel(repository, status)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.open(target, "新しい要約")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isStale)
    }

    @Test
    fun 確かめられなかった理由を出す() = runTest {
        val viewModel = FactCheckViewModel(repository, status)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.open(target, "要約")

        repository.failure = WebSearchException.QuotaExceeded()
        viewModel.check()
        advanceUntilIdle()
        assertEquals(WebSearchException.QuotaExceeded().message, viewModel.uiState.value.error)

        repository.failure = OfflineModeException("api.search.brave.com")
        viewModel.check()
        advanceUntilIdle()
        assertEquals("オフラインモードのため確かめられません", viewModel.uiState.value.error)

        repository.failure = NothingToFactCheckException()
        viewModel.check()
        advanceUntilIdle()
        assertEquals("確かめられる事実の文が見つかりませんでした", viewModel.uiState.value.error)
    }

    @Test
    fun 閉じると途中の確かめもやめる() = runTest {
        repository.gate = CompletableDeferred()
        val viewModel = FactCheckViewModel(repository, status)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.open(target, "要約")
        viewModel.check()
        advanceUntilIdle()

        viewModel.close()
        repository.gate!!.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.target)
        assertNull(viewModel.uiState.value.progress)
        assertTrue(repository.stored.value.isEmpty())
    }

    @Test
    fun 段階の説明と日時の書き方() {
        assertEquals("確かめる文を取り出しています…", progressText(FactCheckProgress.ExtractingClaims))
        assertEquals("2/3 件目を Web で探しています…", progressText(FactCheckProgress.Searching(2, 3)))
        assertEquals("11月15日 9:05", checkedAtText(1_794_701_100_000L, java.time.ZoneId.of("Asia/Tokyo")))
    }
}

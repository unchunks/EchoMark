package com.unchunks.echomark.worker

import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisOutcomeTest {

    @Test
    fun 設定待ちになる失敗() {
        listOf(
            ModelNotAvailableException(),
            NothingToAnalyzeException(),
            LlmException.ApiKeyMissing(ApiProvider.CLAUDE),
            LlmException.InvalidApiKey(401),
            LlmException.BadRequest(404)
        ).forEach { assertEquals(it.javaClass.simpleName, AnalysisOutcome.WAITING_SETUP, classifyAnalysisError(it)) }
    }

    @Test
    fun 拒否は再試行しない() {
        assertEquals(AnalysisOutcome.GIVE_UP, classifyAnalysisError(LlmException.Refused("cyber")))
    }

    @Test
    fun 生成の時間切れは再試行しない() {
        assertEquals(AnalysisOutcome.GIVE_UP, classifyAnalysisError(LlmException.Timeout(180_000L)))
    }

    @Test
    fun 端末内AIのライブラリを初期化できないときは再試行しない() {
        // 同じプロセスでは何度試しても NoClassDefFoundError になるため、試行回数を使い切るまで繰り返さない
        assertEquals(
            AnalysisOutcome.GIVE_UP,
            classifyAnalysisError(LlmException.EngineUnavailable(ExceptionInInitializerError()))
        )
    }

    @Test
    fun 一時的な失敗は再試行() {
        listOf(
            LlmException.RateLimited(),
            LlmException.ServerError(503),
            LlmException.Network(),
            IllegalStateException("boom")
        ).forEach { assertEquals(it.javaClass.simpleName, AnalysisOutcome.RETRY, classifyAnalysisError(it)) }
    }
}

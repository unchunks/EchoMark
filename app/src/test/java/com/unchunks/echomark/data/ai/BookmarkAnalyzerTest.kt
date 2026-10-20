package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** タグ付けと要約の AI が同じなら1回、違えばそれぞれの AI で作ってまとめる。 */
class BookmarkAnalyzerTest {

    private val settings = FakeAppSettingsRepository(backend = LlmBackend.LOCAL)
    private val keys = FakeApiKeyRepository(mapOf(ApiProvider.CLAUDE to "sk-ant-test"))
    private val local = RecordingLlm("端末内")
    private val apiByTask = AiTask.entries.associateWith { RecordingLlm("API-${it.name}") }
    private val analyzer = BookmarkAnalyzer(
        LlmProviderResolver(local, { apiByTask.getValue(it) }, settings, keys),
        settings
    )
    private val input = AnalysisInput(title = "タイトル", text = "本文", kind = ContentKind.WEB_PAGE)

    @Test
    fun 同じAIなら1回の推論でまとめて作る() = runBlocking {
        // チャットの設定は関係しない
        settings.setLlmBackend(AiTask.CHAT, LlmBackend.API)

        val analysis = analyzer.analyze(input, listOf("既存"))

        assertEquals(listOf(AnalysisScope.ALL), local.scopes)
        assertEquals(BookmarkAnalysis("端末内の要約", listOf("端末内のタグ"), "端末内のカテゴリ"), analysis)
        assertEquals(listOf("既存"), local.lastExistingTags)
    }

    @Test
    fun 同じ提供元とモデルのクラウドAPIなら1回の推論でまとめて作る() = runBlocking {
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        settings.setLlmBackend(AiTask.TAGGING, LlmBackend.API)

        analyzer.analyze(input, emptyList())

        assertEquals(listOf(AnalysisScope.ALL), apiByTask.getValue(AiTask.SUMMARY).scopes)
        assertTrue(apiByTask.getValue(AiTask.TAGGING).scopes.isEmpty())
    }

    @Test
    fun 違うAIなら要約とタグを別々に作ってまとめる() = runBlocking {
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)

        val analysis = analyzer.analyze(input, listOf("既存"))

        assertEquals(listOf(AnalysisScope.SUMMARY), apiByTask.getValue(AiTask.SUMMARY).scopes)
        assertEquals(listOf(AnalysisScope.TAGS), local.scopes)
        assertEquals(BookmarkAnalysis("API-SUMMARYの要約", listOf("端末内のタグ"), "端末内のカテゴリ"), analysis)
        assertEquals(listOf("既存"), local.lastExistingTags)
    }

    @Test
    fun 同じ提供元でもモデルが違えば別々に作る() = runBlocking {
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        settings.setLlmBackend(AiTask.TAGGING, LlmBackend.API)
        settings.setApiModel(AiTask.TAGGING, ApiProvider.CLAUDE, "claude-haiku-4-5")

        val analysis = analyzer.analyze(input, emptyList())

        assertEquals(listOf(AnalysisScope.SUMMARY), apiByTask.getValue(AiTask.SUMMARY).scopes)
        assertEquals(listOf(AnalysisScope.TAGS), apiByTask.getValue(AiTask.TAGGING).scopes)
        assertEquals(listOf("API-TAGGINGのタグ"), analysis.tags)
    }

    @Test
    fun 片方の準備ができていなければどちらの推論もしない() = runBlocking {
        settings.setLlmBackend(AiTask.TAGGING, LlmBackend.API)
        settings.setApiProvider(AiTask.TAGGING, ApiProvider.OPENAI)

        try {
            analyzer.analyze(input, emptyList())
            fail("例外が投げられるはず")
        } catch (e: LlmException.ApiKeyMissing) {
            assertEquals(ApiProvider.OPENAI, e.provider)
        }
        assertTrue(local.scopes.isEmpty())
    }

    @Test
    fun 端末内とクラウドAPIに分けたときは端末内を先にし_失敗したらクラウドAPIを呼ばない() = runBlocking {
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        local.failure = ModelNotAvailableException()

        try {
            analyzer.analyze(input, emptyList())
            fail("例外が投げられるはず")
        } catch (e: ModelNotAvailableException) {
            // 端末内モデルが無い
        }
        assertEquals(listOf(AnalysisScope.TAGS), local.scopes)
        assertTrue(apiByTask.getValue(AiTask.SUMMARY).scopes.isEmpty())
    }

    @Test
    fun 両方がクラウドAPIなら要約とタグを同時に呼ぶ() = runTest {
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        settings.setLlmBackend(AiTask.TAGGING, LlmBackend.API)
        settings.setApiProvider(AiTask.TAGGING, ApiProvider.GEMINI)
        keys.setKey(ApiProvider.GEMINI, "AIzaKey")
        apiByTask.getValue(AiTask.SUMMARY).delayMillis = 3_000
        apiByTask.getValue(AiTask.TAGGING).delayMillis = 2_000

        val analysis = analyzer.analyze(input, listOf("既存"))

        // 順番なら 5,000ms。同時なら長い方の 3,000ms
        assertEquals(3_000L, currentTime)
        assertEquals(BookmarkAnalysis("API-SUMMARYの要約", listOf("API-TAGGINGのタグ"), "API-TAGGINGのカテゴリ"), analysis)
        assertEquals(listOf(AnalysisScope.SUMMARY), apiByTask.getValue(AiTask.SUMMARY).scopes)
        assertEquals(listOf(AnalysisScope.TAGS), apiByTask.getValue(AiTask.TAGGING).scopes)
        assertEquals(listOf("既存"), apiByTask.getValue(AiTask.TAGGING).lastExistingTags)
    }

    @Test
    fun 同時に呼んだ片方が失敗したらもう片方はキャンセルして失敗を投げる() = runTest {
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        settings.setLlmBackend(AiTask.TAGGING, LlmBackend.API)
        settings.setApiModel(AiTask.TAGGING, ApiProvider.CLAUDE, "claude-haiku-4-5")
        val summarizer = apiByTask.getValue(AiTask.SUMMARY).apply { delayMillis = 10_000 }
        apiByTask.getValue(AiTask.TAGGING).apply {
            delayMillis = 500
            failure = LlmException.ServerError(503)
        }

        try {
            analyzer.analyze(input, emptyList())
            fail("例外が投げられるはず")
        } catch (e: LlmException.ServerError) {
            // 先に失敗した方の例外を投げる
        }
        assertEquals(500L, currentTime)
        assertEquals(1, summarizer.cancelledCalls)
    }

    @Test
    fun 端末内とクラウドAPIは同時に呼ばず_端末内を先に1つずつ進める() = runTest {
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        local.delayMillis = 2_000
        apiByTask.getValue(AiTask.SUMMARY).delayMillis = 3_000

        analyzer.analyze(input, emptyList())

        assertEquals(5_000L, currentTime)
    }

    /**
     * 呼び出された範囲を記録し、[name] 入りの結果を返す LLM。[failure] があれば記録した後に投げる。
     * [delayMillis] だけ(仮想時間で)待ってから返す。
     */
    private class RecordingLlm(private val name: String) : LlmProvider {
        val scopes = mutableListOf<AnalysisScope>()
        var lastExistingTags: List<String>? = null
        var failure: Exception? = null
        var delayMillis = 0L
        var cancelledCalls = 0

        override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis {
            scopes += scope
            try {
                delay(delayMillis)
            } catch (e: CancellationException) {
                cancelledCalls++
                throw e
            }
            failure?.let { throw it }
            lastExistingTags = existingTags
            return BookmarkAnalysis("${name}の要約", listOf("${name}のタグ"), "${name}のカテゴリ")
        }

        override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String =
            error("not used")
    }
}

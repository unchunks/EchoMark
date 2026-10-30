package com.unchunks.echomark.data.ai

import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.LlmException
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.bookmark.model.SummaryProblem
import com.unchunks.echomark.domain.model.SummaryQuality
import com.unchunks.echomark.domain.model.SummaryFormat
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.model.SummaryStyle
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** タグ付けと要約の AI が同じなら1回、違えばそれぞれの AI で作ってまとめる。 */
class BookmarkAnalyzerTest {

    private val settings = FakeAppSettingsRepository(backend = LlmBackend.LOCAL)

    init {
        // 要約の出来の確認(#25)は、確かめるテストだけで有効にする(ここの AI の Fake は短い要約を返すため)
        settings.summaryStyleFlow.value = SummaryStyle(qualityCheck = false)
    }
    private val keys = FakeApiKeyRepository(mapOf(ApiProvider.CLAUDE to "sk-ant-test"))
    private val local = RecordingLlm("端末内")
    private val apiByTask = AiTask.entries.associateWith { RecordingLlm("API-${it.name}") }
    private val analyzer = BookmarkAnalyzer(
        LlmProviderResolver(LocalLlmProviders { local }, { apiByTask.getValue(it) }, settings, keys),
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
    fun 要約の言語と書き方は設定から読んでAIに渡す() = runBlocking {
        settings.updateSummaryStyle { SummaryStyle(SummaryLanguage.ENGLISH, SummaryFormat.BULLETS, qualityCheck = false) }

        analyzer.analyze(input, emptyList())

        assertEquals(SummaryStyle(SummaryLanguage.ENGLISH, SummaryFormat.BULLETS, qualityCheck = false), local.lastInput!!.style)
    }

    @Test
    fun 設定を変えたら次の処理から反映される() = runBlocking {
        analyzer.analyze(input, emptyList())
        assertEquals(SummaryStyle(qualityCheck = false), local.lastInput!!.style)

        settings.updateSummaryStyle { it.copy(format = SummaryFormat.ONE_LINE) }
        analyzer.analyze(input, emptyList())

        assertEquals(SummaryFormat.ONE_LINE, local.lastInput!!.style.format)
    }


    @Test
    fun 要約の履歴に残す元は要約の設定のAIと設定の言語_書き方() = runBlocking {
        // 要約はクラウド API、タグ付けは端末内。要約の元は「要約」の設定から
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        settings.updateSummaryStyle { SummaryStyle(SummaryLanguage.ENGLISH, SummaryFormat.BULLETS, qualityCheck = false) }

        val origin = analyzer.summaryOrigin()

        assertEquals("${ApiProvider.CLAUDE.displayName} ${settings.setting(AiTask.SUMMARY).apiModel}", origin.generator)
        assertEquals("English・箇条書き", origin.style)
    }

    @Test
    fun 端末内なら端末内のAIと選んだモデル() = runBlocking {
        assertEquals("端末内 AI", analyzer.summaryOrigin().generator)

        settings.setLocalModel(AiTask.SUMMARY, "gemma-3-1b")

        assertEquals("端末内 AI(gemma-3-1b)", analyzer.summaryOrigin().generator)
        assertEquals("日本語・文章", analyzer.summaryOrigin().style)
    }


    // ---- 要約の出来の確認と作り直し(#25) ----

    private val source = "Jetpack Compose は宣言的に UI を組み立てるツールキットです。状態が変わると関係する部分だけが再コンポーズされます。" +
        "不安定な型や毎回生成されるラムダがあると、不要な再コンポーズが起きます。計測しながら改善する手順を紹介します。"
    private val goodSummary = "状態が変わった部分だけが再コンポーズされること、不安定な型を避けること、計測して改善する手順を紹介した記事。"
    private val checkedInput = AnalysisInput(title = "Compose の記事", text = source, kind = ContentKind.WEB_PAGE)

    private fun enableQualityCheck() {
        settings.summaryStyleFlow.value = SummaryStyle(qualityCheck = true)
    }

    @Test
    fun 問題のない要約は作り直さない() = runBlocking {
        enableQualityCheck()
        local.summary = goodSummary

        val analysis = analyzer.analyze(checkedInput, emptyList())

        assertEquals(listOf(AnalysisScope.ALL), local.scopes)
        assertEquals(goodSummary, analysis.summary)
        assertNull(local.lastInput!!.feedback)
    }

    @Test
    fun 問題のある要約は直し方を添えて要約だけを1回作り直す() = runBlocking {
        enableQualityCheck()
        local.summaries = ArrayDeque(listOf("短い。", goodSummary))

        val analysis = analyzer.analyze(checkedInput, listOf("既存"))

        // 最初は全部、作り直しは要約だけ。タグ・カテゴリは最初のものを使う
        assertEquals(listOf(AnalysisScope.ALL, AnalysisScope.SUMMARY), local.scopes)
        assertEquals(goodSummary, analysis.summary)
        assertEquals(listOf("端末内のタグ"), analysis.tags)
        assertEquals("端末内のカテゴリ", analysis.category)
        assertEquals(SummaryQuality.feedback(listOf(SummaryProblem.EMPTY)), local.lastInput!!.feedback)
    }

    @Test
    fun 作り直しても問題が増えるなら最初の要約を使う() = runBlocking {
        enableQualityCheck()
        // 最初: 長すぎる要約だけ(問題 1 つ)。作り直し: 長すぎて、さらに指定と違う言語(問題 2 つ)
        val tooLong = "状態が変わった部分だけを再コンポーズする仕組みと、計測して改善する手順を説明する。".repeat(8)
        val worse = "Compose recomposes only the parts whose state has changed. ".repeat(8)
        local.summaries = ArrayDeque(listOf(tooLong, worse))

        val analysis = analyzer.analyze(checkedInput, emptyList())

        assertEquals(tooLong, analysis.summary)
        assertEquals(2, local.scopes.size)
    }

    @Test
    fun 作り直しは1回だけ_直らなくても何度も繰り返さない() = runBlocking {
        enableQualityCheck()
        local.summaries = ArrayDeque(listOf("短い。", "短い。", "短い。"))

        analyzer.analyze(checkedInput, emptyList())

        assertEquals(2, local.scopes.size)
    }

    @Test
    fun 作り直しに失敗したら最初の要約を使う() = runBlocking {
        enableQualityCheck()
        local.summaries = ArrayDeque(listOf("短い。"))
        local.failOnCall = 2

        val analysis = analyzer.analyze(checkedInput, emptyList())

        assertEquals("短い。", analysis.summary)
    }

    @Test
    fun 設定でオフなら確かめない() = runBlocking {
        settings.summaryStyleFlow.value = SummaryStyle(qualityCheck = false)
        local.summary = "短い。"

        analyzer.analyze(checkedInput, emptyList())

        assertEquals(listOf(AnalysisScope.ALL), local.scopes)
    }

    @Test
    fun タグだけの処理では要約を確かめない() = runBlocking {
        enableQualityCheck()
        local.summary = "短い。"

        analyzer.analyze(checkedInput, emptyList(), AnalysisScope.TAGS)

        assertEquals(listOf(AnalysisScope.TAGS), local.scopes)
    }

    @Test
    fun 要約だけの再処理でも確かめて作り直す() = runBlocking {
        enableQualityCheck()
        local.summaries = ArrayDeque(listOf("短い。", goodSummary))

        val analysis = analyzer.analyze(checkedInput, emptyList(), AnalysisScope.SUMMARY)

        assertEquals(listOf(AnalysisScope.SUMMARY, AnalysisScope.SUMMARY), local.scopes)
        assertEquals(goodSummary, analysis.summary)
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

    @Test
    fun 要約の書き出しのタイトルとチャンネル名を除いて返す() = runBlocking {
        local.summary = "Android開発チャンネルの動画で、コルーチンのキャンセルの仕組みを実例で説明している。"
        val video = AnalysisInput(
            title = "コルーチンのキャンセルを完全に理解する",
            text = "チャンネル: Android開発チャンネル\n\n説明文",
            kind = ContentKind.VIDEO
        )

        val analysis = analyzer.analyze(video, emptyList())

        assertEquals("コルーチンのキャンセルの仕組みを実例で説明している。", analysis.summary)
    }

    /**
     * 呼び出された範囲を記録し、[name] 入りの結果を返す LLM。[failure] があれば記録した後に投げる。
     * [delayMillis] だけ(仮想時間で)待ってから返す。
     */
    @Test
    fun 用途ごとに別の端末内モデルなら別々に作る() = runBlocking {
        val byModel = mutableMapOf<String?, RecordingLlm>()
        val perModel = BookmarkAnalyzer(
            LlmProviderResolver(
                LocalLlmProviders { id -> byModel.getOrPut(id) { RecordingLlm("端末内-$id") } },
                { apiByTask.getValue(it) },
                settings,
                keys
            ),
            settings
        )
        settings.setLocalModel(AiTask.TAGGING, "qwen")

        val analysis = perModel.analyze(input, emptyList())

        assertEquals(listOf(AnalysisScope.TAGS), byModel.getValue("qwen").scopes)
        assertEquals(listOf(AnalysisScope.SUMMARY), byModel.getValue(null).scopes)
        assertEquals(BookmarkAnalysis("端末内-nullの要約", listOf("端末内-qwenのタグ"), "端末内-qwenのカテゴリ"), analysis)
    }

    @Test
    fun 同じ端末内モデルを選んでいれば1回の推論でまとめる() = runBlocking {
        settings.setLocalModel(AiTask.TAGGING, "gemma")
        settings.setLocalModel(AiTask.SUMMARY, "gemma")

        analyzer.analyze(input, emptyList())

        assertEquals(listOf(AnalysisScope.ALL), local.scopes)
    }

    private class RecordingLlm(private val name: String) : LlmProvider {
        val scopes = mutableListOf<AnalysisScope>()
        var lastExistingTags: List<String>? = null
        var failure: Exception? = null
        var delayMillis = 0L
        var cancelledCalls = 0
        /** 返す要約(null なら [name] 入りの要約) */
        var summary: String? = null
        /** 呼び出しごとに順に返す要約(空になったら [summary]・既定へ) */
        var summaries: ArrayDeque<String> = ArrayDeque()
        /** この回数目の呼び出しを失敗させる(1 から数える。0 なら失敗させない) */
        var failOnCall = 0
        private var calls = 0
        var lastInput: AnalysisInput? = null

        override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis {
            scopes += scope
            calls++
            if (calls == failOnCall) throw IllegalStateException("失敗させる呼び出し")
            try {
                delay(delayMillis)
            } catch (e: CancellationException) {
                cancelledCalls++
                throw e
            }
            failure?.let { throw it }
            lastExistingTags = existingTags
            lastInput = input
            return BookmarkAnalysis(summaries.removeFirstOrNull() ?: summary ?: "${name}の要約", listOf("${name}のタグ"), "${name}のカテゴリ")
        }

        override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String =
            error("not used")
    }
}

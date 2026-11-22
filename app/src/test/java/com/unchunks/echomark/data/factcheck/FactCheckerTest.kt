package com.unchunks.echomark.data.factcheck

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.domain.chat.ChatContextLimits
import com.unchunks.echomark.domain.factcheck.ClaimVerdict
import com.unchunks.echomark.domain.factcheck.FactCheckProgress
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.websearch.CollectedWebSource
import com.unchunks.echomark.domain.websearch.WebSearchException
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import com.unchunks.echomark.testing.FakeWebSourceProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 主張の取り出し・検索・照らし合わせの流れ。AI と検索は Fake。 */
class FactCheckerTest {

    /** 指示の種類ごとに決めた応答を返す AI */
    private class ScriptedLlm(
        var claims: String = """{"claims": ["Kotlin 2.3 では K2 コンパイラーが既定になった"]}""",
        var judgement: String = """{"verdict": "supported", "sources": [1], "quote": "K2 コンパイラーが既定になりました"}""",
        var limits: ChatContextLimits = ChatContextLimits.COMPACT,
        var failure: Exception? = null
    ) : LlmProvider by FakeLlmProvider() {
        val prompts = mutableListOf<String>()

        override suspend fun chatContextLimits(): ChatContextLimits = limits

        override suspend fun complete(prompt: String): String {
            prompts += prompt
            failure?.let { throw it }
            return if (prompt.contains("主張を取り出し") || prompt.contains("主張を最大")) claims else judgement
        }
    }

    private val page = CollectedWebSource(
        url = "https://kotlinlang.org/docs/whatsnew23.html",
        title = "What's new in Kotlin 2.3",
        domain = "kotlinlang.org",
        excerpt = "Kotlin 2.3 では、K2 コンパイラーが既定になりました。ビルドも速くなっています。",
        fromPage = true
    )

    /** ページを読めず、検索結果の概要しかないもの(根拠に使わない) */
    private val snippetOnly = page.copy(url = "https://other.example/x", domain = "other.example", fromPage = false)

    private fun checker(llm: LlmProvider?, web: FakeWebSourceProvider): FactChecker {
        val providers = LocalLlmProviders { llm ?: throw ModelNotAvailableException() }
        val resolver = LlmProviderResolver(providers, { FakeLlmProvider() }, FakeAppSettingsRepository(LlmBackend.LOCAL), FakeApiKeyRepository())
        return FactChecker(resolver, web)
    }

    @Test
    fun AIが裏付けたら_抜粋の中にある引用を根拠にする() = runBlocking {
        val web = FakeWebSourceProvider(listOf(page, snippetOnly))
        val progress = mutableListOf<FactCheckProgress>()

        val claims = checker(ScriptedLlm(), web).check("Kotlin 2.3 では K2 コンパイラーが既定になりました。") { progress += it }

        val claim = claims.single()
        assertEquals(ClaimVerdict.SUPPORTED, claim.verdict)
        assertEquals(listOf("kotlinlang.org"), claim.evidence.map { it.domain })
        assertEquals("K2 コンパイラーが既定になりました", claim.evidence.single().quote)
        // 端末内 AI は主張ごとにページ2件、主張そのものを検索語にする
        assertEquals(listOf("Kotlin 2.3 では K2 コンパイラーが既定になった"), web.queries)
        assertEquals(2, web.lastLimit)
        assertEquals(listOf(FactCheckProgress.ExtractingClaims, FactCheckProgress.Searching(1, 1)), progress)
    }

    @Test
    fun AIの引用が抜粋に無ければ_文の比べ合わせで見つけた一節を使う() = runBlocking {
        val llm = ScriptedLlm(judgement = """{"verdict": "supported", "sources": [1], "quote": "どこにも無い作られた一節です"}""")

        val claim = checker(llm, FakeWebSourceProvider(listOf(page))).check("文章") .single()

        val quote = claim.evidence.single().quote
        assertTrue(quote, quote.contains("K2 コンパイラーが既定になりました"))
    }

    @Test
    fun 食い違いはAIの判定で出す() = runBlocking {
        val llm = ScriptedLlm(judgement = """{"verdict": "conflicting", "sources": [1], "quote": "ビルドも速くなっています"}""")

        val claim = checker(llm, FakeWebSourceProvider(listOf(page))).check("文章").single()

        assertEquals(ClaimVerdict.CONFLICTING, claim.verdict)
        assertEquals("ビルドも速くなっています", claim.evidence.single().quote)
    }

    @Test
    fun 分からなければ見つからずにし_調べたページを残す() = runBlocking {
        val llm = ScriptedLlm(judgement = """{"verdict": "not_found", "sources": [], "quote": ""}""")

        val claim = checker(llm, FakeWebSourceProvider(listOf(page))).check("文章").single()

        assertEquals(ClaimVerdict.NOT_FOUND, claim.verdict)
        assertEquals(listOf(""), claim.evidence.map { it.quote })
    }

    @Test
    fun 読めたページが無ければ見つからず_概要は根拠に使わない() = runBlocking {
        val claim = checker(ScriptedLlm(), FakeWebSourceProvider(listOf(snippetOnly))).check("文章").single()

        assertEquals(ClaimVerdict.NOT_FOUND, claim.verdict)
        assertTrue(claim.evidence.isEmpty())
    }

    @Test
    fun AIを使えなければ文から主張を選び_文の比べ合わせだけで確かめる() = runBlocking {
        val web = FakeWebSourceProvider(listOf(page))

        val claims = checker(null, web).check("感想です。Kotlin 2.3 では、K2 コンパイラーが既定になりました。")

        // 数字・英字を含む文を先に選ぶ(主張は3つまで)
        assertEquals(ClaimVerdict.SUPPORTED, claims.first { it.claim.startsWith("Kotlin") }.verdict)
        assertTrue(claims.none { it.verdict == ClaimVerdict.CONFLICTING })
    }

    @Test
    fun AIの応答が壊れていたら作り直させ_それでも駄目なら文から選ぶ() = runBlocking {
        val llm = ScriptedLlm(claims = "主張はありません", judgement = "壊れた出力")

        val claims = checker(llm, FakeWebSourceProvider(listOf(page))).check("Kotlin 2.3 では、K2 コンパイラーが既定になりました。")

        assertEquals(1, claims.size)
        // 作り直しを1回ずつ頼んでいる
        assertEquals(2, llm.prompts.count { it.contains("主張を最大") })
        assertEquals(ClaimVerdict.SUPPORTED, claims.single().verdict)
    }

    @Test
    fun クラウドでは主張とページを多く使う() = runBlocking {
        val llm = ScriptedLlm(
            claims = """{"claims": ["主張の文その1です", "主張の文その2です", "主張の文その3です", "主張の文その4です", "主張の文その5です", "主張の文その6です"]}""",
            limits = ChatContextLimits.forCloud(100_000)
        )
        val web = FakeWebSourceProvider(listOf(page))

        val claims = checker(llm, web).check("文章")

        assertEquals(5, claims.size)
        assertEquals(5, web.queries.size)
        assertEquals(3, web.lastLimit)
    }

    @Test
    fun 検索できなければ途中で止めて知らせる() = runBlocking {
        val web = FakeWebSourceProvider(listOf(page), failure = WebSearchException.QuotaExceeded())

        try {
            checker(ScriptedLlm(), web).check("文章")
            fail("例外が出るはず")
        } catch (e: WebSearchException.QuotaExceeded) {
            // 期待どおり
        }
    }
}

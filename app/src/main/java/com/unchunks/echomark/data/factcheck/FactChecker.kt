package com.unchunks.echomark.data.factcheck

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.ai.StructuredOutput
import com.unchunks.echomark.domain.bookmark.model.EvidenceFinder
import com.unchunks.echomark.domain.chat.ChatContextLimits
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.factcheck.CheckedClaim
import com.unchunks.echomark.domain.factcheck.ClaimVerdict
import com.unchunks.echomark.domain.factcheck.FactCheckEvidence
import com.unchunks.echomark.domain.factcheck.FactCheckProgress
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.websearch.CollectedWebSource
import com.unchunks.echomark.domain.websearch.WebSourceProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * 文章(要約・回答)を Web で確かめる。
 *
 * 1. 文章から、確かめられる事実の主張を取り出す(AI の JSON の出力。形が崩れたら作り直させ、それでも駄目なら文から選ぶ)
 * 2. 主張ごとに Web を検索し、上位のページを読む([WebSourceProvider]。検索の回数の制限・オフラインモードはそちらで守る)
 * 3. 主張とページの抜粋を照らし合わせる。AI に JSON で判定させ、AI の引用は抜粋の中にあるものだけを使う
 *    (無ければ [EvidenceFinder] で見つけた一節)。AI を使えなければ [EvidenceFinder] だけで判定し、食い違いは判定しない
 *
 * ページを読めず検索結果の概要しかないものは、根拠に使わない(概要は保存しない。[com.unchunks.echomark.domain.websearch.WebSearchResult])。
 * 端末内 AI は主張3つ・1つあたりページ2件、クラウドは主張5つ・ページ3件まで。検索は主張の数だけ行う。
 */
class FactChecker @Inject constructor(
    private val resolver: LlmProviderResolver,
    private val webSources: WebSourceProvider
) {
    /**
     * @throws com.unchunks.echomark.domain.websearch.WebSearchException 検索できない(キー・上限など)。途中までの結果は返さない
     */
    suspend fun check(text: String, onProgress: (FactCheckProgress) -> Unit): List<CheckedClaim> {
        val provider = try {
            resolver.resolve(AiTask.CHAT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.i("Web で確かめるのに AI を使えないため、文の比べ合わせだけで確かめる: %s", e.javaClass.simpleName)
            null
        }
        val budget = budgetFor(provider)
        onProgress(FactCheckProgress.ExtractingClaims)
        val claims = extractClaims(provider, text, budget.maxClaims)
        return claims.mapIndexed { index, claim ->
            onProgress(FactCheckProgress.Searching(index + 1, claims.size))
            val sources = webSources.collect(claim, RagSupport.keywordsOf(claim), budget.pagesPerClaim, budget.excerptChars)
                .filter { it.fromPage && it.excerpt.isNotBlank() }
            judge(provider, claim, sources)
        }
    }

    private class Budget(val maxClaims: Int, val pagesPerClaim: Int, val excerptChars: Int)

    private suspend fun budgetFor(provider: LlmProvider?): Budget {
        val limits = provider?.let { runCatching { it.chatContextLimits() }.getOrNull() } ?: ChatContextLimits.COMPACT
        return if (limits == ChatContextLimits.COMPACT) Budget(maxClaims = 3, pagesPerClaim = 2, excerptChars = 450)
        else Budget(maxClaims = 5, pagesPerClaim = 3, excerptChars = 1_200)
    }

    private suspend fun extractClaims(provider: LlmProvider?, text: String, maxClaims: Int): List<String> {
        if (provider != null) {
            try {
                val response = StructuredOutput.generateWithRepair(
                    maxRepairs = 1,
                    label = "確かめる主張",
                    validate = FactCheckPrompts::claimsProblem
                ) { problem, _ -> provider.complete(FactCheckPrompts.claimsPrompt(text, maxClaims, problem)) }
                FactCheckPrompts.parseClaims(response, maxClaims)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("確かめる主張を AI で取り出せなかったため、文から選ぶ: %s", e.javaClass.simpleName)
            }
        }
        return FactCheckPrompts.fallbackClaims(text, maxClaims)
    }

    private suspend fun judge(provider: LlmProvider?, claim: String, sources: List<CollectedWebSource>): CheckedClaim {
        if (sources.isEmpty()) return CheckedClaim(claim, ClaimVerdict.NOT_FOUND)
        // 文字のつながりで見つけた一節(AI の引用が抜粋に無いとき・AI を使えないときに使う)
        val matched = sources.map { source ->
            EvidenceFinder.find(claim, source.excerpt).items.firstOrNull()?.quote
        }
        val judgement = provider?.let { aiJudgement(it, claim, sources) }
        if (judgement == null) {
            val supported = sources.indices.filter { matched[it] != null }
            return if (supported.isEmpty()) {
                CheckedClaim(claim, ClaimVerdict.NOT_FOUND, sources.map { it.evidence("") })
            } else {
                CheckedClaim(claim, ClaimVerdict.SUPPORTED, supported.map { sources[it].evidence(matched[it]!!) })
            }
        }
        if (judgement.verdict == ClaimVerdict.NOT_FOUND) {
            return CheckedClaim(claim, ClaimVerdict.NOT_FOUND, sources.map { it.evidence("") })
        }
        // AI が根拠にしたページ(番号が無ければ、一節の見つかったページ・無ければ全部)
        val cited = judgement.sources.map { it - 1 }
            .ifEmpty { sources.indices.filter { matched[it] != null } }
            .ifEmpty { sources.indices.toList() }
        val evidence = cited.map { index ->
            val source = sources[index]
            val quote = judgement.quote?.takeIf { FactCheckPrompts.isQuoteIn(it, source.excerpt) }
                ?: matched[index]
                ?: source.excerpt
            source.evidence(quote)
        }
        return CheckedClaim(claim, judgement.verdict, evidence)
    }

    private suspend fun aiJudgement(provider: LlmProvider, claim: String, sources: List<CollectedWebSource>): FactCheckPrompts.Judgement? =
        try {
            val excerpts = sources.map { it.domain to it.excerpt }
            val response = StructuredOutput.generateWithRepair(
                maxRepairs = 1,
                label = "主張の照らし合わせ",
                validate = FactCheckPrompts::judgeProblem
            ) { problem, _ -> provider.complete(FactCheckPrompts.judgePrompt(claim, excerpts, problem)) }
            FactCheckPrompts.parseJudgement(response, sources.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("主張を AI で照らし合わせられなかったため、文の比べ合わせだけで確かめる: %s", e.javaClass.simpleName)
            null
        }

    private fun CollectedWebSource.evidence(quote: String) =
        FactCheckEvidence(url = url, title = title, domain = domain, quote = if (quote.isEmpty()) "" else FactCheckPrompts.shortenQuote(quote))
}

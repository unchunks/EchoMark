package com.unchunks.echomark.data.websearch

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.websearch.BraveSearchClient
import com.unchunks.echomark.data.remote.FetchedContent
import com.unchunks.echomark.data.remote.UrlContentFetcher
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.websearch.CollectedWebSource
import com.unchunks.echomark.domain.websearch.WebSearchClient
import com.unchunks.echomark.domain.websearch.WebSearchResult
import com.unchunks.echomark.domain.websearch.WebSourceProvider
import com.unchunks.echomark.domain.websearch.WebSourceText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Web を検索し、上位の結果のページを読んで、質問に関係する部分を抜き出す。
 *
 * ページは [UrlContentFetcher] で読む(ローカルネットワーク・ループバックには接続しない。本文は
 * [com.unchunks.components.web.BodyCleaner] で画面の部品を除いたもの)。回答を長く待たせないよう、1ページを読む時間を
 * [PAGE_CALL_TIMEOUT_SECONDS] 秒までにした接続で読む。読めないページ(ボット対策・PDF・時間切れ)は、検索結果の概要で代える。
 * 概要はその回答のためだけに使い、保存しない([CollectedWebSource.toWebSource])。
 * 同じサイトのページばかりにならないよう、1つのサイトから [MAX_PER_DOMAIN] 件まで。
 */
class WebSourceCollector internal constructor(
    private val searchClient: WebSearchClient,
    private val fetcher: UrlContentFetcher
) : WebSourceProvider {

    @Inject
    constructor(searchClient: WebSearchClient, okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) : this(
        searchClient,
        // 共通の client から作るため、オフラインモードのインターセプターを引き継ぐ
        UrlContentFetcher(okHttpClient.newBuilder().callTimeout(PAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build(), dispatcherProvider)
    )

    override suspend fun collect(
        query: String,
        keywords: List<String>,
        limit: Int,
        excerptChars: Int
    ): List<CollectedWebSource> {
        if (limit <= 0) return emptyList()
        val results = searchClient.search(query, (limit + EXTRA_RESULTS).coerceAtMost(BraveSearchClient.MAX_COUNT))
        val picked = diverse(results).take(limit + EXTRA_PAGES)
        if (picked.isEmpty()) return emptyList()
        val pages = fetchPages(picked)
        return picked.mapIndexed { index, result -> sourceOf(result, pages[index], keywords, excerptChars) }
            .filter { it.excerpt.isNotBlank() }
            // ページを読めたものを先にする(読めなかったものは概要しかない)
            .sortedByDescending { it.fromPage }
            .take(limit)
    }

    private fun sourceOf(result: WebSearchResult, page: FetchedContent?, keywords: List<String>, excerptChars: Int): CollectedWebSource {
        val text = page?.text?.takeIf { it.isNotBlank() }
            ?: return CollectedWebSource(
                url = result.url,
                title = WebSourceText.cleanTitle(result.title),
                domain = result.domain,
                excerpt = WebSourceText.sanitize(result.description, excerptChars),
                fromPage = false
            )
        val passage = RagSupport.bestPassage(text, keywords, excerptChars) ?: text
        return CollectedWebSource(
            url = result.url,
            title = page.title?.takeIf { it.isNotBlank() }?.let { WebSourceText.cleanTitle(it) } ?: result.domain,
            domain = result.domain,
            excerpt = WebSourceText.sanitize(passage, excerptChars),
            fromPage = true
        )
    }

    /** 1つのサイトから [MAX_PER_DOMAIN] 件まで。順位は保つ */
    private fun diverse(results: List<WebSearchResult>): List<WebSearchResult> {
        val perDomain = HashMap<String, Int>()
        return results.filter { result ->
            val count = perDomain.getOrDefault(result.domain, 0)
            (count < MAX_PER_DOMAIN).also { if (it) perDomain[result.domain] = count + 1 }
        }
    }

    /** ページをまとめて読む。読めなかったものは null */
    private suspend fun fetchPages(results: List<WebSearchResult>): List<FetchedContent?> = coroutineScope {
        results.map { result ->
            async {
                fetcher.fetch(result.url)
                    .onFailure { Timber.i("Web の資料のページを読めなかった: %s (%s)", result.domain, it.javaClass.simpleName) }
                    .getOrNull()
            }
        }.awaitAll()
    }

    companion object {
        /** ページを読めないときのために、余分に検索する件数 */
        private const val EXTRA_RESULTS = 4

        /** ページを読めないときのために、余分に読むページの数 */
        private const val EXTRA_PAGES = 1

        private const val MAX_PER_DOMAIN = 2

        /** 1ページを読む時間の上限 */
        private const val PAGE_CALL_TIMEOUT_SECONDS = 6L
    }
}

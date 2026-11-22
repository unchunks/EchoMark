package com.unchunks.echomark.data.websearch

import com.unchunks.components.websearch.BraveSearchClient
import com.unchunks.components.websearch.BraveSearchException
import com.unchunks.components.websearch.BraveWebResult
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.websearch.WebSearchClient
import com.unchunks.echomark.domain.websearch.WebSearchException
import com.unchunks.echomark.domain.websearch.WebSearchResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web 検索(Brave Search API)。API の呼び出しは部品の web-search([BraveSearchClient])が行い、ここでは EchoMark の
 * キーの保存先([ApiKeyRepository])と失敗の知らせ([WebSearchException])をつなぐ。
 *
 * - 通信は共通の OkHttpClient から作るため、オフラインモード([com.unchunks.components.web.NetworkGate])で止まる
 *   (その失敗は WebSearchException に変えずに投げる)
 * - キーはヘッダーにだけ入れ、ログ・例外のメッセージには出さない
 */
@Singleton
class BraveWebSearchClient @Inject constructor(
    private val brave: BraveSearchClient,
    private val apiKeys: ApiKeyRepository
) : WebSearchClient {

    override suspend fun search(query: String, count: Int): List<WebSearchResult> {
        val key = apiKeys.getWebSearchKey()?.takeIf { it.isNotBlank() } ?: throw WebSearchException.KeyMissing()
        return request(key, query, count)
    }

    override suspend fun testKey(apiKey: String?) {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: apiKeys.getWebSearchKey()?.takeIf { it.isNotBlank() }
            ?: throw WebSearchException.KeyMissing()
        request(key, TEST_QUERY, 1)
    }

    private suspend fun request(key: String, query: String, count: Int): List<WebSearchResult> = try {
        brave.search(key, query, count).map { it.toResult() }
    } catch (e: BraveSearchException) {
        throw e.toWebSearchException()
    }

    private fun BraveWebResult.toResult() = WebSearchResult(url = url, title = title, description = description, domain = domain)

    private fun BraveSearchException.toWebSearchException(): WebSearchException = when (this) {
        is BraveSearchException.InvalidKey -> WebSearchException.InvalidKey()
        is BraveSearchException.RateLimited -> WebSearchException.RateLimited(retryAfterSeconds)
        is BraveSearchException.QuotaExceeded -> WebSearchException.QuotaExceeded()
        is BraveSearchException.Failed -> WebSearchException.Failed(detail)
    }

    private companion object {
        /** キーを試すときの検索語 */
        const val TEST_QUERY = "EchoMark"
    }
}

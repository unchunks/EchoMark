package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.websearch.CollectedWebSource
import com.unchunks.echomark.domain.websearch.WebSearchAvailability
import com.unchunks.echomark.domain.websearch.WebSearchStatus
import com.unchunks.echomark.domain.websearch.WebSourceProvider
import kotlinx.coroutines.flow.MutableStateFlow

/** Web 検索を使えるかの Fake。 */
class FakeWebSearchStatus(initial: WebSearchAvailability = WebSearchAvailability()) : WebSearchStatus {
    override val availability = MutableStateFlow(initial)
}

/** Web の資料の Fake。[sources] を返す([failure] があれば投げる)。呼ばれた検索語を [queries] に残す。 */
class FakeWebSourceProvider(
    var sources: List<CollectedWebSource> = emptyList(),
    var failure: Exception? = null
) : WebSourceProvider {
    val queries = mutableListOf<String>()

    /** 最後に渡された件数と抜粋の文字数 */
    var lastLimit: Int? = null
    var lastExcerptChars: Int? = null

    override suspend fun collect(query: String, keywords: List<String>, limit: Int, excerptChars: Int): List<CollectedWebSource> {
        queries += query
        lastLimit = limit
        lastExcerptChars = excerptChars
        failure?.let { throw it }
        return sources.take(limit)
    }
}

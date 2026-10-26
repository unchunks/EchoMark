package com.unchunks.echomark.data.discover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 検索の結果。
 * @property candidates 見つかった候補(検索先を交互に並べ、重複する URL は除いてある)
 * @property failedSources 通信や形式の変化で検索できなかった検索先
 */
data class SearchOutcome(
    val candidates: List<ResourceCandidate>,
    val failedSources: Set<ResourceSource>
)

/**
 * 選ばれた検索先を同時に検索し、結果を1つの一覧にまとめる。
 * 検索先が1つ失敗しても、ほかの検索先の結果は返す(失敗は [SearchOutcome.failedSources] で知らせる)。
 */
@Singleton
class ResourceSearcher internal constructor(
    private val clients: List<ResourceSearchClient>
) {
    @Inject
    constructor(
        hatena: HatenaBookmarkSearchClient,
        youtube: YouTubeSearchClient,
        wikipedia: WikipediaSearchClient,
        arxiv: ArxivSearchClient
    ) : this(listOf(hatena, youtube, wikipedia, arxiv))

    /**
     * [query] を [sources] の各検索先で検索する(検索先ごとに最大 [limitPerSource] 件)。
     * 結果は ARTICLE, VIDEO, ENCYCLOPEDIA, PAPER の順に1件ずつ交互に並べる。
     */
    suspend fun search(query: String, sources: Set<ResourceSource>, limitPerSource: Int): SearchOutcome {
        val trimmed = query.trim()
        val targets = clients
            .filter { it.source in sources }
            .sortedBy { it.source.ordinal }
        if (trimmed.isEmpty() || targets.isEmpty() || limitPerSource <= 0) {
            return SearchOutcome(emptyList(), emptySet())
        }

        val results = coroutineScope {
            targets.map { client ->
                async {
                    try {
                        client.search(trimmed, limitPerSource).take(limitPerSource)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 1つの検索先の失敗で、ほかの検索先の結果まで捨てない
                        Timber.w(e, "資料の検索に失敗しました: %s", client.source)
                        null
                    }
                }
            }.awaitAll()
        }

        val failed = targets.filterIndexed { index, _ -> results[index] == null }.map { it.source }.toSet()
        val lists = results.filterNotNull()
        return SearchOutcome(interleave(lists), failed)
    }

    companion object {
        /** 各リストの先頭から1件ずつ交互に取り、正規化した URL が重複するものは飛ばす */
        internal fun interleave(lists: List<List<ResourceCandidate>>): List<ResourceCandidate> {
            val iterators = lists.map { it.iterator() }
            val seen = mutableSetOf<String>()
            val merged = mutableListOf<ResourceCandidate>()
            var progressed = true
            while (progressed) {
                progressed = false
                for (iterator in iterators) {
                    while (iterator.hasNext()) {
                        val candidate = iterator.next()
                        if (seen.add(normalizeUrl(candidate.url))) {
                            merged += candidate
                            progressed = true
                            break
                        }
                    }
                }
            }
            return merged
        }

        /**
         * 同じ資料かどうかを見るための URL。ホストは小文字で `www.` なし、末尾の「/」・フラグメント・
         * `utm_*` のクエリは除く。YouTube は動画を決める `v` だけを残す(youtu.be も同じ形にそろえる)。
         */
        internal fun normalizeUrl(url: String): String {
            val parsed = url.trim().toHttpUrlOrNull() ?: return url.trim().lowercase().trimEnd('/')
            val host = parsed.host.removePrefix("www.").let { if (it == "m.youtube.com") "youtube.com" else it }
            if (host == "youtu.be") {
                val id = parsed.pathSegments.firstOrNull().orEmpty()
                return "youtube.com/watch?v=$id"
            }
            val path = parsed.encodedPath.trimEnd('/')
            val query = if (host == "youtube.com") {
                parsed.queryParameter("v")?.let { listOf("v" to it) }.orEmpty()
            } else {
                queryPairs(parsed).filterNot { (name, _) -> name.startsWith("utm_", ignoreCase = true) }
            }
            val port = if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) ":${parsed.port}" else ""
            val queryText = if (query.isEmpty()) "" else "?" + query.joinToString("&") { (name, value) ->
                if (value == null) name else "$name=$value"
            }
            return "$host$port$path$queryText"
        }

        private fun queryPairs(url: HttpUrl): List<Pair<String, String?>> =
            (0 until url.querySize).map { url.queryParameterName(it) to url.queryParameterValue(it) }
    }
}

package com.unchunks.echomark.data.discover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ResourceSearcherTest {

    private fun candidate(source: ResourceSource, url: String, title: String = url) =
        ResourceCandidate(url = url, title = title, snippet = "", source = source)

    private class FakeClient(
        override val source: ResourceSource,
        private val results: List<ResourceCandidate> = emptyList(),
        private val error: Throwable? = null
    ) : ResourceSearchClient {
        var lastQuery: String? = null
        var lastLimit: Int? = null

        override suspend fun search(query: String, limit: Int): List<ResourceCandidate> {
            lastQuery = query
            lastLimit = limit
            error?.let { throw it }
            return results
        }
    }

    private fun searcher(vararg clients: ResourceSearchClient) = ResourceSearcher(clients.toList())

    private val all = ResourceSource.entries.toSet()

    @Test
    fun 検索先を記事_動画_百科事典_論文の順に交互に並べる() = runBlocking {
        val searcher = searcher(
            // 渡す順序に関係なく、列挙の順で並ぶ
            FakeClient(ResourceSource.PAPER, listOf(candidate(ResourceSource.PAPER, "https://arxiv.org/abs/1"), candidate(ResourceSource.PAPER, "https://arxiv.org/abs/2"))),
            FakeClient(ResourceSource.ENCYCLOPEDIA, listOf(candidate(ResourceSource.ENCYCLOPEDIA, "https://ja.wikipedia.org/wiki/A"))),
            FakeClient(ResourceSource.VIDEO, listOf(candidate(ResourceSource.VIDEO, "https://www.youtube.com/watch?v=aaaaaaaaaaa"), candidate(ResourceSource.VIDEO, "https://www.youtube.com/watch?v=bbbbbbbbbbb"))),
            FakeClient(
                ResourceSource.ARTICLE,
                listOf(
                    candidate(ResourceSource.ARTICLE, "https://a.example/1"),
                    candidate(ResourceSource.ARTICLE, "https://a.example/2"),
                    candidate(ResourceSource.ARTICLE, "https://a.example/3")
                )
            )
        )

        val outcome = searcher.search("kotlin", all, limitPerSource = 5)

        assertEquals(
            listOf(
                "https://a.example/1", "https://www.youtube.com/watch?v=aaaaaaaaaaa", "https://ja.wikipedia.org/wiki/A", "https://arxiv.org/abs/1",
                "https://a.example/2", "https://www.youtube.com/watch?v=bbbbbbbbbbb", "https://arxiv.org/abs/2",
                "https://a.example/3"
            ),
            outcome.candidates.map { it.url }
        )
        assertEquals(emptySet<ResourceSource>(), outcome.failedSources)
    }

    @Test
    fun 検索先が1つ失敗してもほかの結果を返し失敗した検索先を知らせる() = runBlocking {
        val searcher = searcher(
            FakeClient(ResourceSource.ARTICLE, error = IOException("HTTP 500")),
            FakeClient(ResourceSource.VIDEO, listOf(candidate(ResourceSource.VIDEO, "https://www.youtube.com/watch?v=aaaaaaaaaaa"))),
            FakeClient(ResourceSource.ENCYCLOPEDIA, error = IllegalStateException("形式が変わった")),
            FakeClient(ResourceSource.PAPER, listOf(candidate(ResourceSource.PAPER, "https://arxiv.org/abs/1")))
        )

        val outcome = searcher.search("kotlin", all, limitPerSource = 5)

        assertEquals(listOf(ResourceSource.VIDEO, ResourceSource.PAPER), outcome.candidates.map { it.source })
        assertEquals(setOf(ResourceSource.ARTICLE, ResourceSource.ENCYCLOPEDIA), outcome.failedSources)
    }

    @Test
    fun 結果が空の検索先は失敗として扱わない() = runBlocking {
        val searcher = searcher(FakeClient(ResourceSource.ARTICLE, emptyList()))

        val outcome = searcher.search("kotlin", setOf(ResourceSource.ARTICLE), limitPerSource = 5)

        assertEquals(emptyList<ResourceCandidate>(), outcome.candidates)
        assertEquals(emptySet<ResourceSource>(), outcome.failedSources)
    }

    @Test
    fun 検索先をまたいで同じ資料のURLは正規化して1件にする() = runBlocking {
        val searcher = searcher(
            FakeClient(
                ResourceSource.ARTICLE,
                listOf(
                    candidate(ResourceSource.ARTICLE, "https://Example.com/post/?utm_source=x&id=1#top"),
                    candidate(ResourceSource.ARTICLE, "https://www.youtube.com/watch?v=aaaaaaaaaaa&t=30s")
                )
            ),
            FakeClient(
                ResourceSource.VIDEO,
                listOf(
                    candidate(ResourceSource.VIDEO, "https://youtu.be/aaaaaaaaaaa"),
                    candidate(ResourceSource.VIDEO, "https://www.youtube.com/watch?v=bbbbbbbbbbb")
                )
            ),
            FakeClient(ResourceSource.ENCYCLOPEDIA, listOf(candidate(ResourceSource.ENCYCLOPEDIA, "http://example.com/post?id=1")))
        )

        val outcome = searcher.search("kotlin", all, limitPerSource = 5)

        // 動画の youtu.be は記事側の youtube.com/watch と同じ資料なので先に来た動画が残り、
        // 百科事典の http://example.com/post?id=1 は記事の1件目と同じなので飛ばされる
        assertEquals(
            listOf(
                "https://Example.com/post/?utm_source=x&id=1#top",
                "https://youtu.be/aaaaaaaaaaa",
                "https://www.youtube.com/watch?v=bbbbbbbbbbb"
            ),
            outcome.candidates.map { it.url }
        )
    }

    @Test
    fun URLの正規化() {
        fun n(url: String) = ResourceSearcher.normalizeUrl(url)

        assertEquals("example.com/post", n("https://www.Example.com/post/"))
        assertEquals("example.com/post?id=1", n("http://example.com/post?utm_source=a&id=1&utm_medium=b#frag"))
        assertEquals("example.com:8080/a", n("http://example.com:8080/a"))
        assertEquals("youtube.com/watch?v=abc12345678", n("https://www.youtube.com/watch?v=abc12345678&list=PL1&t=3"))
        assertEquals("youtube.com/watch?v=abc12345678", n("https://m.youtube.com/watch?v=abc12345678"))
        assertEquals("youtube.com/watch?v=abc12345678", n("https://youtu.be/abc12345678?si=x"))
        assertEquals("not a url", n("Not A URL/"))
    }

    @Test
    fun 検索先ごとの件数を上限にしクエリを整えて渡す() = runBlocking {
        val article = FakeClient(
            ResourceSource.ARTICLE,
            List(5) { candidate(ResourceSource.ARTICLE, "https://a.example/$it") }
        )
        val searcher = searcher(article)

        val outcome = searcher.search("  kotlin  ", setOf(ResourceSource.ARTICLE), limitPerSource = 3)

        assertEquals(3, outcome.candidates.size)
        assertEquals("kotlin", article.lastQuery)
        assertEquals(3, article.lastLimit)
    }

    @Test
    fun 選ばれていない検索先は呼ばない() = runBlocking {
        val article = FakeClient(ResourceSource.ARTICLE, listOf(candidate(ResourceSource.ARTICLE, "https://a.example/1")))
        val video = FakeClient(ResourceSource.VIDEO, listOf(candidate(ResourceSource.VIDEO, "https://www.youtube.com/watch?v=aaaaaaaaaaa")))
        val searcher = searcher(article, video)

        val outcome = searcher.search("kotlin", setOf(ResourceSource.VIDEO), limitPerSource = 5)

        assertEquals(listOf(ResourceSource.VIDEO), outcome.candidates.map { it.source })
        assertEquals(null, article.lastQuery)
    }

    @Test
    fun 空の検索語は何も検索しない() = runBlocking {
        val article = FakeClient(ResourceSource.ARTICLE, listOf(candidate(ResourceSource.ARTICLE, "https://a.example/1")))

        val outcome = searcher(article).search("   ", all, limitPerSource = 5)

        assertEquals(SearchOutcome(emptyList(), emptySet()), outcome)
        assertEquals(null, article.lastQuery)
    }

    @Test
    fun キャンセルは失敗として握りつぶさずに投げ直す() {
        val searcher = searcher(FakeClient(ResourceSource.ARTICLE, error = CancellationException("cancelled")))

        try {
            runBlocking { searcher.search("kotlin", all, limitPerSource = 5) }
            fail("CancellationException が投げられるはず")
        } catch (e: CancellationException) {
            // 期待どおり
        }
    }
}

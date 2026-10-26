package com.unchunks.echomark.data.discover

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.testBookmark
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** テーマで資料を探し、AI に選ばせ、選んだものを保存する。 */
class ResourceDiscoveryTest {

    private val bookmarks = FakeBookmarkRepository()

    private class FakeClient(
        override val source: ResourceSource,
        private val results: List<ResourceCandidate>
    ) : ResourceSearchClient {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String, limit: Int): List<ResourceCandidate> {
            queries += query
            return results.take(limit)
        }
    }

    /** complete に [response] を返す(または [failure] を投げる)LLM。渡された指示を記録する。 */
    private class SelectingLlm(private val response: String, private val failure: Exception? = null) : LlmProvider {
        var lastPrompt: String? = null
        override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis =
            error("not used")
        override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String =
            error("not used")
        override suspend fun complete(prompt: String): String {
            lastPrompt = prompt
            failure?.let { throw it }
            return response
        }
    }

    private val articles = FakeClient(
        ResourceSource.ARTICLE,
        listOf(
            ResourceCandidate("https://qiita.com/a", "Kotlin コルーチン入門", "基本の説明", ResourceSource.ARTICLE),
            ResourceCandidate("https://zenn.dev/b", "コルーチンの例外処理", "例外の扱い", ResourceSource.ARTICLE)
        )
    )
    private val videos = FakeClient(
        ResourceSource.VIDEO,
        listOf(ResourceCandidate("https://www.youtube.com/watch?v=abcdefghijk", "Kotlin コルーチン解説", "", ResourceSource.VIDEO))
    )
    private val papers = FakeClient(
        ResourceSource.PAPER,
        listOf(ResourceCandidate("https://arxiv.org/abs/1", "Coroutines in Kotlin", "", ResourceSource.PAPER))
    )

    private fun discovery(llm: LlmProvider) = ResourceDiscovery(
        searcher = ResourceSearcher(listOf(articles, videos, papers)),
        llmProviderResolver = LlmProviderResolver(llm, { llm }, FakeAppSettingsRepository(LlmBackend.LOCAL), FakeApiKeyRepository()),
        bookmarkRepository = bookmarks
    )

    @Test
    fun AIが選んだものを理由つきで先に並べ_ほかの候補を後に並べる() = runBlocking {
        val llm = SelectingLlm("""{"picks": [{"n": 2, "reason": "動画で全体がつかめる"}]}""")
        val steps = mutableListOf<DiscoveryStep>()

        val result = discovery(llm).discover("Kotlin のコルーチン", setOf(ResourceSource.ARTICLE, ResourceSource.VIDEO)) { steps += it }

        assertEquals(listOf(DiscoveryStep.SEARCHING, DiscoveryStep.SELECTING), steps)
        // 候補は種類を交互に並べて AI に渡す: [1] 記事 [2] 動画 [3] 記事
        assertTrue(llm.lastPrompt!!.contains("[2] (動画) Kotlin コルーチン解説"))
        val first = result.resources.first()
        assertEquals("Kotlin コルーチン解説", first.candidate.title)
        assertTrue(first.isRecommended)
        assertEquals("動画で全体がつかめる", first.reason)
        assertEquals(3, result.resources.size)
        assertFalse(result.resources.drop(1).any { it.isRecommended })
        assertNull(result.aiError)
        // 検索語はテーマの語をつないだもの
        assertEquals(listOf("Kotlin コルーチン"), articles.queries)
    }

    @Test
    fun AIが使えなくても検索の結果をおすすめ無しで返す() = runBlocking {
        val result = discovery(SelectingLlm("", failure = ModelNotAvailableException()))
            .discover("Kotlin のコルーチン", setOf(ResourceSource.ARTICLE))

        assertEquals(2, result.resources.size)
        assertTrue(result.resources.none { it.isRecommended })
        assertTrue(result.aiError is ModelNotAvailableException)
    }

    @Test
    fun 保存済みの資料には印を付ける() = runBlocking {
        bookmarks.bookmarks.value = listOf(testBookmark(1).copy(contentUri = "https://qiita.com/a/"))

        val result = discovery(SelectingLlm("""{"picks": [1]}""")).discover("Kotlin", setOf(ResourceSource.ARTICLE))

        assertTrue(result.resources.first { it.candidate.url == "https://qiita.com/a" }.isSaved)
        assertFalse(result.resources.first { it.candidate.url == "https://zenn.dev/b" }.isSaved)
    }

    @Test
    fun 論文は英語の語で探し_英語の語が無ければ探さない() = runBlocking {
        val llm = SelectingLlm("""{"picks": []}""")

        discovery(llm).discover("Kotlin のコルーチン", setOf(ResourceSource.PAPER))
        discovery(llm).discover("睡眠の質", setOf(ResourceSource.PAPER))

        assertEquals(listOf("Kotlin"), papers.queries)
    }

    @Test
    fun 候補が無ければAIを呼ばない() = runBlocking {
        val llm = SelectingLlm("""{"picks": []}""")
        val empty = ResourceDiscovery(
            searcher = ResourceSearcher(listOf(FakeClient(ResourceSource.ARTICLE, emptyList()))),
            llmProviderResolver = LlmProviderResolver(llm, { llm }, FakeAppSettingsRepository(LlmBackend.LOCAL), FakeApiKeyRepository()),
            bookmarkRepository = bookmarks
        )

        val result = empty.discover("Kotlin", setOf(ResourceSource.ARTICLE))

        assertTrue(result.resources.isEmpty())
        assertNull(llm.lastPrompt)
    }

    @Test
    fun 選んだ資料をURLのブックマークとして保存し_新しく保存した数を返す() = runBlocking {
        bookmarks.bookmarks.value = listOf(testBookmark(1).copy(contentUri = "https://qiita.com/a"))

        val added = discovery(SelectingLlm("")).save(
            listOf(
                ResourceCandidate("https://qiita.com/a", "保存済み", "", ResourceSource.ARTICLE),
                ResourceCandidate("https://zenn.dev/b", "新しい記事", "", ResourceSource.ARTICLE)
            )
        )

        assertEquals(1, added)
        assertEquals(listOf("https://qiita.com/a" to "保存済み", "https://zenn.dev/b" to "新しい記事"), bookmarks.savedUrls.map { it.first to it.second })
    }
}

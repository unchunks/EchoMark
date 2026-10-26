package com.unchunks.echomark.data.ai.discover

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.remote.PageLink
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceSelectorTest {

    private val candidates = listOf(
        PageLink("https://example.com/1", "Kotlin 入門"),
        PageLink("https://example.com/2", "ログイン"),
        PageLink("https://example.com/3", "Compose の使い方")
    )

    /** 端末内 AI の代わり。指示の内容は見ず、決められた応答を返す。 */
    private fun selector(response: String, bookmarks: FakeBookmarkRepository): ResourceSelector {
        val llm: LlmProvider = object : LlmProvider by FakeLlmProvider() {
            override suspend fun complete(prompt: String): String = response
        }
        val resolver = LlmProviderResolver(llm, { llm }, FakeAppSettingsRepository(LlmBackend.LOCAL), FakeApiKeyRepository())
        return ResourceSelector(resolver, bookmarks)
    }

    @Test
    fun 選ばれた資料だけをリンクの文字をタイトルにしてブックマークに保存する() = runBlocking {
        val bookmarks = FakeBookmarkRepository()

        val picked = selector("{\"picks\": [1, 3]}", bookmarks).selectAndSave("Kotlin", candidates)

        assertEquals(listOf(candidates[0], candidates[2]), picked)
        assertEquals(
            listOf(
                Triple("https://example.com/1", "Kotlin 入門", null),
                Triple("https://example.com/3", "Compose の使い方", null)
            ),
            bookmarks.savedUrls
        )
    }

    @Test
    fun 役立つものが無ければ何も保存しない() = runBlocking {
        val bookmarks = FakeBookmarkRepository()

        val picked = selector("{\"picks\": []}", bookmarks).selectAndSave("Kotlin", candidates)

        assertEquals(emptyList<PageLink>(), picked)
        assertEquals(emptyList<Triple<String, String?, String?>>(), bookmarks.savedUrls)
    }

    @Test
    fun 調べたいことが空なら_AIを呼ばずに何もしない() = runBlocking {
        val bookmarks = FakeBookmarkRepository()

        val picked = selector("{\"picks\": [1]}", bookmarks).selectAndSave("  ", candidates)

        assertEquals(emptyList<PageLink>(), picked)
        assertEquals(emptyList<Triple<String, String?, String?>>(), bookmarks.savedUrls)
    }
}

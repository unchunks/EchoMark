package com.unchunks.echomark.data.ai.discover

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.remote.PageLink
import com.unchunks.echomark.domain.bookmark.model.AttachmentError
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.AttachmentRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceSelectorTest {

    private val candidates = listOf(
        ResourceCandidate.web(PageLink("https://example.com/1", "Kotlin 入門")),
        ResourceCandidate.web(PageLink("https://example.com/2", "ログイン")),
        ResourceCandidate.web(PageLink("https://example.com/3", "Compose の使い方"))
    )

    /** 端末内のファイルを取り込む。"bad" で始まる URI は形式が対応外として失敗する。 */
    private val attachments = object : AttachmentRepository {
        override suspend fun importFile(uri: String): StoredAttachment {
            if (uri.startsWith("bad")) throw AttachmentException(AttachmentError.Unsupported("application/x-bad"))
            return StoredAttachment(filePath = "$uri.pdf", mimeType = "application/pdf", fileName = uri, fileSize = 10)
        }
    }

    /** 端末内 AI の代わり。指示の内容は見ず、決められた応答を返す。 */
    private fun selector(response: String, bookmarks: FakeBookmarkRepository): ResourceSelector {
        val llm: LlmProvider = object : LlmProvider by FakeLlmProvider() {
            override suspend fun complete(prompt: String): String = response
        }
        val resolver = LlmProviderResolver(llm, { llm }, FakeAppSettingsRepository(LlmBackend.LOCAL), FakeApiKeyRepository())
        return ResourceSelector(resolver, bookmarks, attachments)
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
    fun 端末内のファイルは取り込んでからファイルのブックマークにする() = runBlocking {
        val bookmarks = FakeBookmarkRepository()
        val files = listOf(
            ResourceCandidate.file("content://docs/1", "論文.pdf"),
            ResourceCandidate.file("bad://docs/2", "壊れた.bin")
        )

        val picked = selector("{\"picks\": [1, 2]}", bookmarks).selectAndSave("論文", files)

        // 取り込めなかったファイルは飛ばし、残りは保存する
        assertEquals(listOf(files[0]), picked)
        assertEquals(1, bookmarks.savedFiles.size)
        assertEquals("論文.pdf", bookmarks.savedFiles[0].second)
    }

    @Test
    fun 役立つものが無ければ何も保存しない() = runBlocking {
        val bookmarks = FakeBookmarkRepository()

        val picked = selector("{\"picks\": []}", bookmarks).selectAndSave("Kotlin", candidates)

        assertEquals(emptyList<ResourceCandidate>(), picked)
        assertEquals(emptyList<Triple<String, String?, String?>>(), bookmarks.savedUrls)
    }

    @Test
    fun 調べたいことが空なら_AIを呼ばずに何もしない() = runBlocking {
        val bookmarks = FakeBookmarkRepository()

        val picked = selector("{\"picks\": [1]}", bookmarks).selectAndSave("  ", candidates)

        assertEquals(emptyList<ResourceCandidate>(), picked)
        assertEquals(emptyList<Triple<String, String?, String?>>(), bookmarks.savedUrls)
    }
}

package com.unchunks.echomark.data.ai.discover

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.remote.PageLink
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.BookmarkRepository
import javax.inject.Inject

/**
 * 候補の中から AI に資料を選ばせ、選ばれたものをブックマークに保存する。
 * 端末内の AI で選ぶ(設定の「チャット」の実行場所を使う)。AI の応答が崩れていれば、何も保存しない。
 */
class ResourceSelector @Inject constructor(
    private val resolver: LlmProviderResolver,
    private val bookmarks: BookmarkRepository
) {
    /** 選んで保存した資料を返す。候補が無い・役立つものが無いときは空。 */
    suspend fun selectAndSave(topic: String, candidates: List<PageLink>): List<PageLink> {
        if (topic.isBlank() || candidates.isEmpty()) return emptyList()
        val response = resolver.resolve(AiTask.CHAT).complete(ResourceSelection.prompt(topic, candidates))
        val picked = ResourceSelection.parsePicks(response, candidates.size).map { candidates[it] }
        picked.forEach { bookmarks.saveUrlBookmark(it.url, it.text, memo = null) }
        return picked
    }
}

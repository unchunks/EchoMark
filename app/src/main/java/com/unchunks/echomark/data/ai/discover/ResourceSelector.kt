package com.unchunks.echomark.data.ai.discover

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AttachmentRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * 候補の中から AI に資料を選ばせ、選ばれたものをブックマークに保存する。
 * 端末内の AI で選ぶ(設定の「チャット」の実行場所を使う)。AI の応答が崩れていれば、何も保存しない。
 * 保存は1件ずつ行い、保存できなかったもの(ファイルの形式が対応外など)は飛ばして残りを続ける。
 */
class ResourceSelector @Inject constructor(
    private val resolver: LlmProviderResolver,
    private val bookmarks: BookmarkRepository,
    private val attachments: AttachmentRepository
) {
    /** 選んで保存できた資料を返す。候補が無い・役立つものが無いときは空。 */
    suspend fun selectAndSave(topic: String, candidates: List<ResourceCandidate>): List<ResourceCandidate> {
        if (topic.isBlank() || candidates.isEmpty()) return emptyList()
        val response = resolver.resolve(AiTask.CHAT).complete(ResourceSelection.prompt(topic, candidates))
        val picked = ResourceSelection.parsePicks(response, candidates.size).map { candidates[it] }
        return picked.filter { candidate ->
            try {
                save(candidate)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 1件の失敗(対応外の形式・読めないファイルなど)で残りを止めない
                Timber.w(e, "資料を保存できなかった: %s", candidate.title)
                false
            }
        }
    }

    private suspend fun save(candidate: ResourceCandidate) {
        when (candidate.source) {
            ResourceCandidate.Source.WEB -> bookmarks.saveUrlBookmark(candidate.ref, candidate.title, memo = null)
            ResourceCandidate.Source.FILE -> {
                val stored = attachments.importFile(candidate.ref)
                bookmarks.saveFileBookmark(stored, candidate.title, memo = null)
            }
        }
    }
}

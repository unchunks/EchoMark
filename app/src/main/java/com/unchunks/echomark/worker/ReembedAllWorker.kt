package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ReembedAllWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val bookmarkRepository: BookmarkRepository,
    private val embeddingProvider: EmbeddingProvider
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val currentVersion = embeddingProvider.modelVersion
        val allIds = bookmarkRepository.getAllBookmarkIds()

        for (id in allIds) {
            val existingVersion = bookmarkRepository.getEmbeddingModelVersion(id)
            if (existingVersion == currentVersion) continue // 既に最新版なのでスキップ

            val bookmark = bookmarkRepository.getBookmarkById(id) ?: continue
            try {
                val text = bookmark.title + "\n" + (bookmark.content ?: "")
                val vector = embeddingProvider.embedDocument(text)
                bookmarkRepository.saveEmbedding(id, vector, currentVersion)
            } catch (e: Exception) {
                continue // 1件失敗しても他のブックマークの処理は止めない
            }
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "reembed_all_bookmarks"
    }
}

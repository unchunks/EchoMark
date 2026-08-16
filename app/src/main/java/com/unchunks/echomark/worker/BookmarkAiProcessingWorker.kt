package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class BookmarkAiProcessingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository,
    private val llmProviderResolver: LlmProviderResolver,
    private val embeddingProvider: EmbeddingProvider
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.d("doWork")
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        if (bookmarkId == -1L) return Result.failure()

        val bookmark = repository.getBookmarkById(bookmarkId) ?: return Result.failure()

        return try {
            val textToProcess = bookmark.title + "\n" + (bookmark.content ?: "")
            val provider = llmProviderResolver.resolve()

            val summary = provider.summarize(textToProcess)
            repository.updateSummary(bookmarkId, summary)

            val tags = provider.generateTags(textToProcess)
            repository.saveTags(bookmarkId, tags)

            val category = provider.categorize(textToProcess)
            repository.updateCategory(bookmarkId, category)

            val vector = embeddingProvider.embedDocument(textToProcess)
            repository.saveEmbedding(bookmarkId, vector, embeddingProvider.modelVersion)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_BOOKMARK_ID = "bookmark_id"
    }
}

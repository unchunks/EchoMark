package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 保存されたブックマークの AI 処理(要約・タグ・カテゴリ・埋め込み)を行う。
 * 各工程は冪等: 済みの工程はスキップするため、リトライしても全体をやり直さない。
 */
@HiltWorker
class BookmarkAiProcessingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository,
    private val llmProviderResolver: LlmProviderResolver,
    private val embeddingProvider: EmbeddingProvider
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.d("doWork attempt=$runAttemptCount")
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        if (bookmarkId == -1L) return Result.failure()

        val bookmark = repository.getBookmarkById(bookmarkId) ?: return Result.failure()

        if (runAttemptCount >= MAX_ATTEMPTS) {
            repository.updateAiStatus(bookmarkId, AiStatus.FAILED)
            return Result.failure()
        }

        return try {
            repository.updateAiStatus(bookmarkId, AiStatus.PROCESSING)
            val textToProcess = bookmark.title + "\n" + (bookmark.content ?: "")
            var waitingModel = false

            // 工程1: 要約・タグ・カテゴリ(要約が既にあればスキップ)
            if (bookmark.summary.isNullOrBlank()) {
                try {
                    val analysis = llmProviderResolver.resolve().analyze(textToProcess)
                    repository.updateSummary(bookmarkId, analysis.summary)
                    repository.saveTags(bookmarkId, analysis.tags)
                    repository.updateCategory(bookmarkId, analysis.category)
                } catch (e: ModelNotAvailableException) {
                    // LLM モデル未取得でも埋め込みは実行する。モデル取得後に再実行される
                    Timber.i("LLM モデル未取得のため解析を保留: id=$bookmarkId")
                    waitingModel = true
                }
            }

            // 工程2: 埋め込み(現行モデルバージョンで保存済みならスキップ)
            if (repository.getEmbeddingModelVersion(bookmarkId) != embeddingProvider.modelVersion) {
                val vector = embeddingProvider.embedDocument(textToProcess)
                repository.saveEmbedding(bookmarkId, vector, embeddingProvider.modelVersion)
            }

            repository.updateAiStatus(
                bookmarkId,
                if (waitingModel) AiStatus.WAITING_MODEL else AiStatus.DONE
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AI 処理に失敗: id=$bookmarkId attempt=$runAttemptCount")
            if (runAttemptCount >= MAX_ATTEMPTS) {
                repository.updateAiStatus(bookmarkId, AiStatus.FAILED)
                Result.failure()
            } else {
                repository.updateAiStatus(bookmarkId, AiStatus.PENDING)
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_BOOKMARK_ID = "bookmark_id"
        private const val MAX_ATTEMPTS = 3
    }
}

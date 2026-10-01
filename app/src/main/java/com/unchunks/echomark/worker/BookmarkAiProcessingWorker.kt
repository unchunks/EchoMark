package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import com.unchunks.echomark.domain.provider.LlmException
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

        // 削除済みなら何もしない(処理すべきものが無いだけなので成功で終える)
        val bookmark = repository.getBookmarkById(bookmarkId) ?: return Result.success()

        if (runAttemptCount >= MAX_ATTEMPTS) {
            repository.updateAiStatus(bookmarkId, AiStatus.FAILED)
            return Result.failure()
        }

        return try {
            repository.updateAiStatus(bookmarkId, AiStatus.PROCESSING)
            val textToProcess = bookmark.title + "\n" + (bookmark.content ?: "")
            var analysisOutcome = AnalysisOutcome.DONE

            // 工程1: 要約・タグ・カテゴリ(要約が既にあればスキップ)
            if (bookmark.summary.isNullOrBlank()) {
                try {
                    val analysis = llmProviderResolver.resolve().analyze(textToProcess)
                    repository.updateSummary(bookmarkId, analysis.summary)
                    repository.saveTags(bookmarkId, analysis.tags)
                    repository.updateCategory(bookmarkId, analysis.category)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    analysisOutcome = classifyAnalysisError(e)
                    // 再試行で回復しうる失敗は外側の catch でリトライさせる
                    if (analysisOutcome == AnalysisOutcome.RETRY) throw e
                    // モデル未取得・キー未設定などでも埋め込みは実行する。設定後に再実行される
                    Timber.i("解析を保留/中止: id=$bookmarkId outcome=$analysisOutcome (${e.javaClass.simpleName})")
                }
            }

            // 工程2: 埋め込み(現行モデルバージョンで保存済みならスキップ)
            // 埋め込みモデルが無い環境ではスキップし、検索はキーワードのみで動かす
            try {
                if (repository.getEmbeddingModelVersion(bookmarkId) != embeddingProvider.modelVersion) {
                    val vector = embeddingProvider.embedDocument(textToProcess)
                    repository.saveEmbedding(bookmarkId, vector, embeddingProvider.modelVersion)
                }
            } catch (e: EmbeddingUnavailableException) {
                Timber.i("埋め込みモデルが無いため埋め込みをスキップ: id=$bookmarkId")
            }

            repository.updateAiStatus(
                bookmarkId,
                when (analysisOutcome) {
                    AnalysisOutcome.DONE -> AiStatus.DONE
                    AnalysisOutcome.WAITING_SETUP -> AiStatus.WAITING_MODEL
                    // 拒否など再試行しても結果が変わらないもの。設定画面から手動で再処理できる
                    AnalysisOutcome.GIVE_UP, AnalysisOutcome.RETRY -> AiStatus.FAILED
                }
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

/** 解析(要約・タグ)工程の結果。 */
internal enum class AnalysisOutcome {
    DONE,
    /** モデル未取り込み・API キー未設定/無効・モデル ID 不正など、設定の変更を待つ */
    WAITING_SETUP,
    /** 拒否・安全フィルタなど、再試行しても結果が変わらない */
    GIVE_UP,
    /** レート制限・通信断・サーバー障害など、時間をおけば回復しうる */
    RETRY
}

/** 解析工程の例外を、ワーカーの振る舞いに対応づける。 */
internal fun classifyAnalysisError(e: Exception): AnalysisOutcome = when (e) {
    is ModelNotAvailableException,
    is LlmException.ApiKeyMissing,
    is LlmException.InvalidApiKey,
    is LlmException.BadRequest -> AnalysisOutcome.WAITING_SETUP
    is LlmException.Refused -> AnalysisOutcome.GIVE_UP
    else -> AnalysisOutcome.RETRY
}

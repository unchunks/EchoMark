package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unchunks.components.embedding.EmbeddingInputBuilder
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.embedding.EmbeddingUnavailableException
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

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
                val text = EmbeddingInputBuilder.build(
                    bookmark.title, bookmark.summary, bookmark.content, embeddingProvider.profile
                )
                val vector = embeddingProvider.embedDocument(text)
                bookmarkRepository.saveEmbedding(id, vector, currentVersion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EmbeddingUnavailableException) {
                // モデルが無い・ライブラリを初期化できない。残りも同じ結果になるため、1件ごとに読み込みを試さずにやめる
                // (次の起動・モデルの切り替えで積み直される)
                Timber.i("埋め込みモデルを使えないため、埋め込みの作り直しをやめる")
                return Result.success()
            } catch (e: Exception) {
                continue // 1件失敗しても他のブックマークの処理は止めない
            }
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "reembed_all_bookmarks"

        /** 埋め込みの作り直し(未作成・旧版のものだけ)を積む。起動時は KEEP、モデルを切り替えたときは REPLACE を渡す。 */
        fun enqueue(workManager: WorkManager, policy: ExistingWorkPolicy) {
            workManager.enqueueUniqueWork(
                WORK_NAME,
                policy,
                OneTimeWorkRequestBuilder<ReembedAllWorker>()
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build()
            )
        }
    }
}

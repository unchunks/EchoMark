package com.unchunks.echomark.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.unchunks.components.embedding.EmbeddingModelProfile
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.embedding.EmbeddingUnavailableException
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.testBookmark
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 埋め込みの作り直しのワーカー。 */
@RunWith(AndroidJUnit4::class)
class ReembedAllWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fakeBookmarks = FakeBookmarkRepository().apply {
        bookmarks.value = (1L..3L).map { testBookmark(id = it, title = "メモ$it") }
    }
    private val saved = mutableListOf<Long>()

    private val repository: BookmarkRepository = object : BookmarkRepository by fakeBookmarks {
        override suspend fun getAllBookmarkIds(): List<Long> = fakeBookmarks.bookmarks.value.map(Bookmark::id)
        override suspend fun getEmbeddingModelVersion(bookmarkId: Long): String? = null
        override suspend fun saveEmbedding(bookmarkId: Long, vector: FloatArray, modelVersion: String) {
            saved += bookmarkId
        }
    }

    /** 1件目だけ [firstFailure] で失敗し、それ以降は成功する埋め込み。 */
    private class ScriptedEmbedding(private val firstFailure: Exception) : EmbeddingProvider {
        var calls = 0
        override val profile: EmbeddingModelProfile = EmbeddingModelProfile.GEMMA_V1.copy(modelVersion = "test")
        override suspend fun embedDocument(text: String): FloatArray {
            calls++
            if (calls == 1) throw firstFailure
            return FloatArray(dimensions)
        }
        override suspend fun embedQuery(text: String): FloatArray = error("not used")
    }

    private suspend fun run(embedding: EmbeddingProvider): ListenableWorker.Result =
        TestListenableWorkerBuilder<ReembedAllWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReembedAllWorker(appContext, workerParameters, repository, embedding)
            })
            .build()
            .doWork()

    @Test
    fun 埋め込みモデルが使えなければ残りは試さずに終える() = runBlocking {
        // ライブラリの初期化の失敗(LinkageError)も、埋め込みの側でこの例外になる
        val embedding = ScriptedEmbedding(EmbeddingUnavailableException(NoClassDefFoundError("TextEmbedder")))

        assertEquals(ListenableWorker.Result.success(), run(embedding))
        assertEquals(1, embedding.calls)
        assertEquals(emptyList<Long>(), saved)
    }

    @Test
    fun 一件の失敗では止めずに残りを作り直す() = runBlocking {
        val embedding = ScriptedEmbedding(IllegalStateException("boom"))

        assertEquals(ListenableWorker.Result.success(), run(embedding))
        assertEquals(3, embedding.calls)
        assertEquals(listOf(2L, 3L), saved)
    }
}

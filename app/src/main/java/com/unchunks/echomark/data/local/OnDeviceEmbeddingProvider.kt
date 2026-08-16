package com.unchunks.echomark.data.local

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextFormatContext
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceEmbeddingProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : EmbeddingProvider {

    override val dimensions = 768
    override val modelVersion = "embedding-gemma-300m-mediapipe-v1"

    private var textEmbedder: TextEmbedder? = null
    private val initMutex = Mutex()

    private suspend fun ensureInitialized(): TextEmbedder {
        textEmbedder?.let { return it }
        return initMutex.withLock {
            textEmbedder ?: run {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("embeddinggemma-300m/embedding_gemma.task")
                    .build()
                val options = TextEmbedderOptions.builder()
                    .setBaseOptions(baseOptions)
                    .build()
                TextEmbedder.createFromOptions(context, options).also { textEmbedder = it }
            }
        }
    }

    override suspend fun embedDocument(text: String): FloatArray =
        embedInternal(text, TextEmbedder.EmbeddingType.RETRIEVAL_DOCUMENT)

    override suspend fun embedQuery(text: String): FloatArray =
        embedInternal(text, TextEmbedder.EmbeddingType.RETRIEVAL_QUERY)

    private suspend fun embedInternal(
        text: String,
        taskType: TextEmbedder.EmbeddingType
    ): FloatArray = withContext(dispatcherProvider.io) {
        val embedder = ensureInitialized()
        val formatContext = TextFormatContext.builder().setTaskType(taskType).build()
        val result = embedder.embed(text, formatContext)
        result.embeddingResult().embeddings().first().floatEmbedding()
    }
}

package com.unchunks.echomark.di

import android.content.Context
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.embedding.OnDeviceEmbeddingProvider
import com.unchunks.echomark.data.ai.AiPerfLog
import com.unchunks.echomark.data.ai.model.EmbeddingModelManager
import com.unchunks.echomark.domain.provider.EmbeddingModels
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EmbeddingModule {
    /** ユーザーが取り込んだモデル([EmbeddingModelManager])があればそれを、無ければ同梱の EmbeddingGemma を使う */
    @Provides
    @Singleton
    fun provideEmbeddingProvider(
        @ApplicationContext context: Context,
        modelManager: EmbeddingModelManager,
        dispatcherProvider: DispatcherProvider
    ): EmbeddingProvider = OnDeviceEmbeddingProvider(
        context,
        modelManager,
        dispatcherProvider,
        bundledAssetPath = EmbeddingModels.BUNDLED_ASSET_PATH,
        onModelLoaded = { sourceKey, durationMs -> AiPerfLog.record("embedding.load", durationMs, sourceKey) }
    )
}

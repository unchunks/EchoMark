package com.unchunks.echomark.di

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.embedding.OnDeviceEmbeddingProvider
import com.unchunks.echomark.data.ai.AiPerfLog
import com.unchunks.echomark.data.ai.embedding.ApiCloudEmbedder
import com.unchunks.echomark.data.ai.embedding.CloudEmbedder
import com.unchunks.echomark.data.ai.embedding.SwitchingEmbeddingProvider
import com.unchunks.echomark.data.ai.model.EmbeddingModelManager
import com.unchunks.echomark.domain.provider.EmbeddingModels
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.worker.ReembedAllWorker
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddingModule {

    /** クラウドの埋め込み(OpenAI・Gemini の埋め込み API) */
    @Binds
    abstract fun bindCloudEmbedder(impl: ApiCloudEmbedder): CloudEmbedder

    companion object {
        /**
         * 設定に合わせて、端末内の埋め込みモデルかクラウドの埋め込み API を使う。
         * 端末内は、ユーザーが取り込んだモデル([EmbeddingModelManager])があればそれを、無ければ同梱の EmbeddingGemma を使う。
         * 作る場所を変えたら、全ブックマークの埋め込みを作り直す(済んだ分は版が合うので飛ばす)。
         */
        @Provides
        @Singleton
        fun provideEmbeddingProvider(
            @ApplicationContext context: Context,
            modelManager: EmbeddingModelManager,
            cloud: CloudEmbedder,
            appSettings: AppSettingsRepository,
            workManager: WorkManager,
            dispatcherProvider: DispatcherProvider
        ): EmbeddingProvider = SwitchingEmbeddingProvider(
            onDevice = OnDeviceEmbeddingProvider(
                context,
                modelManager,
                dispatcherProvider,
                bundledAssetPath = EmbeddingModels.BUNDLED_ASSET_PATH,
                onModelLoaded = { sourceKey, durationMs -> AiPerfLog.record("embedding.load", durationMs, sourceKey) }
            ),
            cloud = cloud,
            appSettings = appSettings,
            dispatcherProvider = dispatcherProvider,
            onEngineChanged = { ReembedAllWorker.enqueue(workManager, ExistingWorkPolicy.REPLACE) }
        )
    }
}

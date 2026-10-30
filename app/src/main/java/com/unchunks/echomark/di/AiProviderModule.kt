package com.unchunks.echomark.di

import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.data.ai.api.AndroidAttachmentLoader
import com.unchunks.echomark.data.ai.api.ApiLlmProvider
import com.unchunks.echomark.data.ai.api.ApiLlmProviderFactory
import com.unchunks.echomark.data.ai.api.AttachmentLoader
import com.unchunks.echomark.data.ai.local.LocalLlmProvider
import com.unchunks.echomark.data.ai.model.AndroidModelDownloadClient
import com.unchunks.echomark.data.ai.model.DownloadedModelInstaller
import com.unchunks.echomark.data.ai.model.ModelDownloadClient
import com.unchunks.echomark.data.ai.model.ModelManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AiProviderModule {

    /** 端末内 AI。用途ごとに選んだ端末内モデルで動く LlmProvider を返す */
    @Binds
    abstract fun bindLocalLlmProviders(impl: LocalLlmProvider): LocalLlmProviders

    @Binds
    abstract fun bindApiLlmProviderFactory(impl: ApiLlmProvider): ApiLlmProviderFactory

    /** おすすめのモデルのダウンロードを端末の DownloadManager に任せる */
    @Binds
    abstract fun bindModelDownloadClient(impl: AndroidModelDownloadClient): ModelDownloadClient

    /** ダウンロードしたモデルを一覧に加える */
    @Binds
    abstract fun bindDownloadedModelInstaller(impl: ModelManager): DownloadedModelInstaller

    @Binds
    abstract fun bindAttachmentLoader(impl: AndroidAttachmentLoader): AttachmentLoader
}

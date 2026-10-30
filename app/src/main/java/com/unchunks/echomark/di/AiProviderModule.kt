package com.unchunks.echomark.di

import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.data.ai.api.AndroidAttachmentLoader
import com.unchunks.echomark.data.ai.api.ApiLlmProvider
import com.unchunks.echomark.data.ai.api.ApiLlmProviderFactory
import com.unchunks.echomark.data.ai.api.AttachmentLoader
import com.unchunks.echomark.data.ai.local.LocalLlmProvider
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

    @Binds
    abstract fun bindAttachmentLoader(impl: AndroidAttachmentLoader): AttachmentLoader
}

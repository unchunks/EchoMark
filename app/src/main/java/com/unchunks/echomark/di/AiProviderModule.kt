package com.unchunks.echomark.di

import com.unchunks.echomark.data.ai.api.AndroidAttachmentLoader
import com.unchunks.echomark.data.ai.api.ApiLlmProvider
import com.unchunks.echomark.data.ai.api.ApiLlmProviderFactory
import com.unchunks.echomark.data.ai.api.AttachmentLoader
import com.unchunks.echomark.data.ai.local.LocalLlmProvider
import com.unchunks.echomark.di.qualifier.LocalAi
import com.unchunks.echomark.domain.provider.LlmProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AiProviderModule {

    @Binds
    @LocalAi
    abstract fun bindLocalLlmProvider(impl: LocalLlmProvider): LlmProvider

    @Binds
    abstract fun bindApiLlmProviderFactory(impl: ApiLlmProvider): ApiLlmProviderFactory

    @Binds
    abstract fun bindAttachmentLoader(impl: AndroidAttachmentLoader): AttachmentLoader
}

package com.unchunks.echomark.di

import com.unchunks.echomark.data.ai.api.ApiLlmProvider
import com.unchunks.echomark.data.ai.local.LocalLlmProvider
import com.unchunks.echomark.di.qualifier.ApiAi
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
    @ApiAi
    abstract fun bindApiLlmProvider(impl: ApiLlmProvider): LlmProvider
}
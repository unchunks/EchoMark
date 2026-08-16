package com.unchunks.echomark.di

import com.unchunks.echomark.data.local.OnDeviceEmbeddingProvider
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddingModule {
    @Binds
    abstract fun bindEmbeddingProvider(impl: OnDeviceEmbeddingProvider): EmbeddingProvider
}

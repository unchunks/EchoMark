package com.unchunks.echomark.di

import com.unchunks.echomark.data.repository.AiSetupRepositoryImpl
import com.unchunks.echomark.domain.repository.AiSetupRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** チャット画面まわりで使うバインディング。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {

    @Binds
    abstract fun bindAiSetupRepository(
        impl: AiSetupRepositoryImpl
    ): AiSetupRepository
}

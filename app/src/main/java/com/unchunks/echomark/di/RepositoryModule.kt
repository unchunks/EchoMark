package com.unchunks.echomark.di

import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.data.repository.BookmarkRepositoryImpl
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.data.repository.ChatRepositoryImpl
import com.unchunks.echomark.data.repository.AppSettingsRepositoryImpl
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.data.repository.ApiKeyRepositoryImpl
import com.unchunks.echomark.data.security.KeystoreSecretCipher
import com.unchunks.echomark.data.security.SecretCipher
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.data.repository.TagRepositoryImpl
import com.unchunks.echomark.domain.repository.TagRepository
import com.unchunks.echomark.data.backup.DataManagementRepositoryImpl
import com.unchunks.echomark.domain.repository.DataManagementRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindBookmarkRepository(
        impl: BookmarkRepositoryImpl
    ): BookmarkRepository

    @Binds
    abstract fun bindChatRepository(
        impl: ChatRepositoryImpl
    ): ChatRepository

    @Binds
    abstract fun bindAppSettingsRepository(
        impl: AppSettingsRepositoryImpl
    ): AppSettingsRepository

    @Binds
    abstract fun bindApiKeyRepository(
        impl: ApiKeyRepositoryImpl
    ): ApiKeyRepository

    @Binds
    abstract fun bindTagRepository(
        impl: TagRepositoryImpl
    ): TagRepository

    @Binds
    abstract fun bindDataManagementRepository(
        impl: DataManagementRepositoryImpl
    ): DataManagementRepository

    @Binds
    abstract fun bindSecretCipher(
        impl: KeystoreSecretCipher
    ): SecretCipher
}

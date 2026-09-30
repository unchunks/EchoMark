package com.unchunks.echomark.di

import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.data.repository.BookmarkRepositoryImpl
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.data.repository.ChatRepositoryImpl
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
}

package com.unchunks.echomark.di

import com.unchunks.echomark.data.factcheck.FactCheckRepositoryImpl
import com.unchunks.echomark.domain.factcheck.FactCheckRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Web で確かめる(ブックマークの要約・チャットの回答) */
@Module
@InstallIn(SingletonComponent::class)
abstract class FactCheckModule {
    @Binds
    abstract fun bindFactCheckRepository(impl: FactCheckRepositoryImpl): FactCheckRepository
}

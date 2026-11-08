package com.unchunks.echomark.di

import com.unchunks.echomark.data.websearch.BraveSearchClient
import com.unchunks.echomark.data.websearch.SettingsWebSearchStatus
import com.unchunks.echomark.data.websearch.WebSourceCollector
import com.unchunks.echomark.domain.websearch.WebSearchClient
import com.unchunks.echomark.domain.websearch.WebSearchStatus
import com.unchunks.echomark.domain.websearch.WebSourceProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Web 検索(Brave Search API) */
@Module
@InstallIn(SingletonComponent::class)
abstract class WebSearchModule {
    @Binds
    abstract fun bindWebSearchClient(impl: BraveSearchClient): WebSearchClient

    @Binds
    abstract fun bindWebSourceProvider(impl: WebSourceCollector): WebSourceProvider

    @Binds
    abstract fun bindWebSearchStatus(impl: SettingsWebSearchStatus): WebSearchStatus
}

package com.unchunks.echomark.di

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.websearch.BraveSearchClient
import com.unchunks.components.websearch.SearchUserAgents
import com.unchunks.echomark.data.websearch.BraveWebSearchClient
import com.unchunks.echomark.data.websearch.SettingsWebSearchStatus
import com.unchunks.echomark.data.websearch.WebSourceCollector
import com.unchunks.echomark.domain.websearch.WebSearchClient
import com.unchunks.echomark.domain.websearch.WebSearchStatus
import com.unchunks.echomark.domain.websearch.WebSourceProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Web 検索(Brave Search API) */
@Module
@InstallIn(SingletonComponent::class)
abstract class WebSearchModule {
    @Binds
    abstract fun bindWebSearchClient(impl: BraveWebSearchClient): WebSearchClient

    @Binds
    abstract fun bindWebSourceProvider(impl: WebSourceCollector): WebSourceProvider

    @Binds
    abstract fun bindWebSearchStatus(impl: SettingsWebSearchStatus): WebSearchStatus

    companion object {
        /** 部品の Brave Search API のクライアント。1秒あたりの回数の制限をアプリ全体で共有するため、1つだけ作る */
        @Provides
        @Singleton
        fun provideBraveSearchClient(
            okHttpClient: OkHttpClient,
            dispatcherProvider: DispatcherProvider,
            userAgents: SearchUserAgents
        ): BraveSearchClient = BraveSearchClient(okHttpClient, dispatcherProvider, userAgents)
    }
}

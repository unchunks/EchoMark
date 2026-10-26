package com.unchunks.echomark.di

import com.unchunks.components.websearch.SearchUserAgents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** 共通 OkHttpClient。大容量ダウンロードでは newBuilder() でタイムアウトを上書きすること。 */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /** 資料を探すときに検索先へ名乗る User-Agent */
    @Provides
    fun provideSearchUserAgents(): SearchUserAgents = SearchUserAgents(
        app = "EchoMark/1.0 (Android; +https://github.com/unchunks/EchoMark)",
        browser = "${SearchUserAgents.DEFAULT_BROWSER} EchoMark/1.0"
    )
}

package com.unchunks.echomark.di

import com.unchunks.components.websearch.SearchUserAgents
import com.unchunks.echomark.data.network.DefaultOfflineModeController
import com.unchunks.echomark.data.network.NetworkGate
import com.unchunks.echomark.data.network.OfflineModeController
import com.unchunks.echomark.data.network.OfflineModeInterceptor
import com.unchunks.echomark.data.network.SettingsNetworkGate
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

    /**
     * 共通 OkHttpClient。大容量ダウンロードでは newBuilder() でタイムアウトを上書きすること。
     * アプリの OkHttp の通信は、すべてこの client(か、ここから newBuilder() で作った client)で行う。
     * オフラインモードの [OfflineModeInterceptor] はここで付け、newBuilder() で作った client にも引き継がれる
     * (`OkHttpClient.Builder()` から新しく作るとオフラインモードを抜けるため、`NetworkPolicyCoverageTest` が断る)。
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(gate: NetworkGate): OkHttpClient = baseOkHttpClient(gate)

    /** [provideOkHttpClient] の中身(テストから組み立てを確かめる) */
    internal fun baseOkHttpClient(gate: NetworkGate): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(OfflineModeInterceptor(gate))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /** 通信してよいか(オフラインモード)を決める、ただ1つの場所 */
    @Provides
    fun provideNetworkGate(impl: SettingsNetworkGate): NetworkGate = impl

    @Provides
    fun provideOfflineModeController(impl: DefaultOfflineModeController): OfflineModeController = impl

    /** 資料を探すときに検索先へ名乗る User-Agent */
    @Provides
    fun provideSearchUserAgents(): SearchUserAgents = SearchUserAgents(
        app = "EchoMark/1.0 (Android; +https://github.com/unchunks/EchoMark)",
        browser = "${SearchUserAgents.DEFAULT_BROWSER} EchoMark/1.0"
    )
}

package com.unchunks.echomark.data.network

import com.unchunks.echomark.data.ai.model.ModelCatalogDownloader
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.Lazy
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** オフラインモードを切り替える。設定画面から使い、ViewModel のテストでは Fake に差し替える。 */
interface OfflineModeController {
    /**
     * オンにすると、すぐに通信を止める(実行中の OkHttp の通信を取り消し、モデルのダウンロードもやめる)。
     * オフにすると、オフラインモードの間に処理待ちのまま残った処理を積み直す
     * (夜間にまとめて処理する設定なら積まず、夜を待つ)。
     */
    suspend fun setOfflineMode(enabled: Boolean)
}

@Singleton
class DefaultOfflineModeController @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val gate: SettingsNetworkGate,
    private val okHttpClient: OkHttpClient,
    private val modelDownloader: Lazy<ModelCatalogDownloader>,
    private val bookmarkRepository: Lazy<BookmarkRepository>
) : OfflineModeController {

    override suspend fun setOfflineMode(enabled: Boolean) {
        if (enabled) {
            // 設定の書き込みを待つ間の通信も止める
            gate.remember(true)
            appSettings.setOfflineMode(true)
            gate.remember(true)
            // 共通の client から作った client は Dispatcher を共有するため、実行中の通信をまとめて取り消せる
            okHttpClient.dispatcher.cancelAll()
            // DownloadManager は端末の側で通信を続けるため、やめてもらう(オフにしてから、もう一度ダウンロードする)
            modelDownloader.get().cancelForOfflineMode()
        } else {
            appSettings.setOfflineMode(false)
            gate.remember(false)
            val count = bookmarkRepository.get().enqueueWaitingProcessing(nightly = false)
            if (count > 0) Timber.i("オフラインモードを切ったので、待っていた処理を積んだ: %d 件", count)
        }
    }
}

package com.unchunks.echomark.data.network

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * アプリが今ネットワークに接続してよいか(オフラインモード)を決める、ただ1つの場所。
 *
 * 通信の経路はすべてここを通す(一覧と守り方は `docs/offline-mode.md`)。
 * - OkHttp を使う経路(本文の取得・GitHub・フィード・リンクの確認・資料探し・クラウドの文字起こし・画像・地図のタイル):
 *   共通の OkHttpClient([com.unchunks.echomark.di.NetworkModule])に付けた [OfflineModeInterceptor] が断る。
 *   各所の client は共通の client から `newBuilder()` で作るため、インターセプターを引き継ぐ
 * - OkHttp を通らない経路(Claude の SDK・DownloadManager・WebView): 呼ぶ前に [ensureOnline] で確かめる
 *
 * 新しい経路が守りを抜けていないかは `NetworkPolicyCoverageTest` がソースを調べて確かめる。
 */
interface NetworkGate {
    /** オフラインモードがオンか。OkHttp のスレッド・WebView のコールバックから同期で呼ぶ */
    fun isOffline(): Boolean

    /** オフラインモードの移り変わり(画面の表示用) */
    val offlineMode: Flow<Boolean>
}

/** オフラインモードの間に通信しようとした。通信の失敗(IOException)の一種として扱い、各所の失敗の扱いに乗せる */
class OfflineModeException(what: String) : IOException("オフラインモードのため通信しません($what)")

/** オフラインモードなら [OfflineModeException] を投げる。OkHttp を通らない通信の前に呼ぶ */
fun NetworkGate.ensureOnline(what: String) {
    if (isOffline()) throw OfflineModeException(what)
}

/** オフラインモードで断った通信の失敗か(ほかの例外に包まれていても、原因をたどって見つける) */
fun Throwable.isOfflineModeBlock(): Boolean =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).any { it is OfflineModeException }

private const val MAX_CAUSE_DEPTH = 10

/**
 * オフラインモードの間、接続を始める前に断る(アプリケーションのインターセプター。接続・名前解決より前に動く)。
 * 共通の OkHttpClient に付けるため、そこから作ったすべての client で効く。
 */
class OfflineModeInterceptor(private val gate: NetworkGate) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (gate.isOffline()) throw OfflineModeException(chain.request().url.host)
        return chain.proceed(chain.request())
    }
}

/**
 * 設定([AppSettingsRepository.offlineMode])を読む [NetworkGate]。
 * OkHttp のスレッドから同期で読めるよう、値を覚えておき、設定が変わったら書き換える。まだ一度も読んでいないとき
 * (起動直後)は、その場で設定を読んでから答える(読む前に通信を通さない)。
 */
@Singleton
class SettingsNetworkGate @Inject constructor(
    private val appSettings: AppSettingsRepository,
    dispatcherProvider: DispatcherProvider
) : NetworkGate {

    @Volatile
    private var current: Boolean? = null

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "オフラインモードの設定を読めない") }
    )

    init {
        scope.launch { appSettings.offlineMode.collect { current = it } }
    }

    override val offlineMode: Flow<Boolean> = appSettings.offlineMode

    override fun isOffline(): Boolean =
        current ?: runBlocking { appSettings.offlineMode.first() }.also { current = it }

    /**
     * 切り替えを、設定の書き込みを待たずにすぐ効かせる([OfflineModeController] から呼ぶ)。
     * オンにするときは書き込みより前に呼び、その間の通信も止める。
     */
    internal fun remember(offline: Boolean) {
        current = offline
    }
}

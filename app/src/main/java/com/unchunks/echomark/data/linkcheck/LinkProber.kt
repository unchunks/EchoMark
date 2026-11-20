package com.unchunks.echomark.data.linkcheck

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.web.PublicAddressInterceptor
import com.unchunks.components.web.PublicOnlyDns
import com.unchunks.components.web.isAllowedRedirect
import com.unchunks.components.web.isPublicAddress
import com.unchunks.echomark.domain.linkcheck.LinkCheckPolicy
import com.unchunks.echomark.domain.linkcheck.LinkProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * リンク先が今も開けるかを1件確認する(#94)。ページの中身は読まない(本文を取り込むのは別の処理)。
 *
 * 保存したリンクは利用者以外が書いた入力なので、[com.unchunks.echomark.data.remote.UrlContentFetcher]・
 * [com.unchunks.echomark.data.feed.FeedFetcher] と同じ守りで取得する:
 * - ローカルネットワーク・ループバックのアドレスには接続しない(名前解決の結果と接続先の両方で確かめる)。
 *   その場合は失敗ではなく [LinkProbeResult.NotCheckable] にする(切れたとは言えない)
 * - リダイレクトは自前で追い(最大 [MAX_REDIRECTS] 回)、各段で http / https 以外と https → http の格下げを拒否する
 * - 時間は全体で [CALL_TIMEOUT_SECONDS] 秒まで
 *
 * まず HEAD で確かめ、開けない応答(404 など)のときは GET(先頭の1バイトだけ)でもう一度確かめる
 * (HEAD を断る・HEAD だけ 404 を返すサイトがあるため)。GET の本文は読まず、すぐ閉じる。
 */
@Singleton
class LinkProber internal constructor(
    okHttpClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    /** 接続してよいアドレスか(テストではローカルのサーバーを許可する) */
    isAllowedAddress: (InetAddress) -> Boolean
) {
    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, ::isPublicAddress)

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .dns(PublicOnlyDns(okHttpClient.dns, isAllowedAddress))
        .addNetworkInterceptor(PublicAddressInterceptor(isAllowedAddress))
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** [url] を確認する。取り消し以外の例外は投げず、結果で返す。 */
    suspend fun probe(url: String): LinkProbeResult = withContext(dispatcherProvider.io) {
        val start = url.trim().toHttpUrlOrNull() ?: return@withContext LinkProbeResult.NotCheckable
        try {
            probeBlocking(start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            classify(e)
        } catch (e: IllegalArgumentException) {
            LinkProbeResult.NotCheckable
        }
    }

    private fun probeBlocking(start: HttpUrl): LinkProbeResult {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            val response = request(current)
            response.use {
                if (it.isRedirect) {
                    val next = it.header("Location")?.let { location -> current.resolve(location) }
                        ?: return LinkProbeResult.Failed("移動先が正しくありません", networkLevel = false)
                    // 安全でないリダイレクト(別の種類の URL・https から http)は、その先を確かめずに開けるとはみなさない
                    if (!isAllowedRedirect(current, next)) return LinkProbeResult.Failed("安全でない移動先です", networkLevel = false)
                    current = next
                    return@repeat
                }
                return LinkCheckPolicy.classifyStatus(it.code)
            }
        }
        return LinkProbeResult.Failed("移動が多すぎます", networkLevel = false)
    }

    /** HEAD で確かめ、開けない応答のときは GET でもう一度確かめた応答を返す */
    private fun request(url: HttpUrl): Response {
        val head = client.newCall(build(url).head().build()).execute()
        if (head.isSuccessful || head.isRedirect) return head
        head.close()
        // Range: 本文を取らず先頭だけ。対応しないサイトは全体を返すが、読まずに閉じるので通信は最小限
        return client.newCall(build(url).get().header("Range", "bytes=0-0").build()).execute()
    }

    private fun build(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "*/*")

    private fun classify(e: IOException): LinkProbeResult = when {
        // 接続してはいけないアドレス(名前解決の結果・IP アドレスの直接指定)。切れたとは言えない
        e.message?.startsWith(BLOCKED_MESSAGE_PREFIX) == true -> LinkProbeResult.NotCheckable
        e is UnknownHostException -> LinkProbeResult.Failed("サイトが見つかりません", networkLevel = true)
        e is SocketTimeoutException || e is InterruptedIOException -> LinkProbeResult.Failed("応答がありません(時間切れ)", networkLevel = true)
        e is ConnectException -> LinkProbeResult.Failed("接続できません", networkLevel = true)
        e is SSLException -> LinkProbeResult.Failed("安全な接続ができません(証明書など)", networkLevel = true)
        else -> LinkProbeResult.Failed("通信できませんでした", networkLevel = true)
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val CALL_TIMEOUT_SECONDS = 20L

        /** [PublicOnlyDns]・[PublicAddressInterceptor] が接続を断るときの例外の文言の先頭(部品側の文言。変わったらテストが失敗する) */
        private const val BLOCKED_MESSAGE_PREFIX = "ローカルネットワークのアドレスには接続しません"
        private const val USER_AGENT = "Mozilla/5.0 (Android) EchoMark link check"
    }
}

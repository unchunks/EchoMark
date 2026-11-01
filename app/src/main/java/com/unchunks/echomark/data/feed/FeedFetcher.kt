package com.unchunks.echomark.data.feed

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.web.PublicAddressInterceptor
import com.unchunks.components.web.PublicOnlyDns
import com.unchunks.components.web.isAllowedRedirect
import com.unchunks.components.web.isPublicAddress
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 取得したフィード(またはページ)の本文 */
class FeedBody(
    val bytes: ByteArray,
    /** Content-Type の文字コード(無ければ null。XML の宣言や HTML の meta から判定する) */
    val charsetName: String?,
    /** リダイレクトを追った後の URL */
    val finalUrl: String,
    val etag: String?,
    val lastModified: String?
) {
    /** UTF-8 などの文字コードで文字列にしたもの(ページの HTML から探すときに使う) */
    fun text(): String = String(bytes, charsetName?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8)
}

/** [FeedFetcher.fetch] の結果 */
sealed interface FeedResponse {
    class Body(val body: FeedBody) : FeedResponse

    /** 前回から変わっていない(304) */
    data object NotModified : FeedResponse
}

/** 取得に失敗した。[message] は利用者にそのまま見せる */
class FeedFetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * フィードやページを取得する。フィードは利用者以外が書いた入力なので、次のようにする([com.unchunks.echomark.data.remote.UrlContentFetcher] と同じ守り):
 * - ローカルネットワーク・ループバックのアドレスには接続しない([isPublicAddress]。名前解決の結果と接続先の両方で確かめる)
 * - リダイレクトは自前で追い(最大 [MAX_REDIRECTS] 回)、各段で http / https 以外と https → http の格下げを拒否する
 * - 本文は [MAX_BODY_BYTES] まで。超えたら失敗(Content-Length で先に断り、無い場合も読みながら数える)
 * - 時間は全体で [CALL_TIMEOUT_SECONDS] 秒まで
 * 前回の ETag・Last-Modified があれば条件付きの取得にして、変わっていなければ本文を取らない。
 */
@Singleton
class FeedFetcher internal constructor(
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

    /** [url](http / https)を取得する。失敗は [FeedFetchException]。 */
    suspend fun fetch(url: String, etag: String? = null, lastModified: String? = null): FeedResponse =
        withContext(dispatcherProvider.io) {
            try {
                fetchBlocking(url, etag, lastModified)
            } catch (e: FeedFetchException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw FeedFetchException("URL の形式が正しくありません", e)
            } catch (e: IOException) {
                throw FeedFetchException("取得できませんでした(通信できないか、サイトが応答しません)", e)
            }
        }

    private fun fetchBlocking(url: String, etag: String?, lastModified: String?): FeedResponse {
        var current: HttpUrl = url.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
                .apply {
                    // 条件付きの取得は、最初のリクエストだけ(リダイレクト先は別の URL かもしれないため)
                    if (current.toString() == url.toHttpUrl().toString()) {
                        etag?.let { header("If-None-Match", it) }
                        lastModified?.let { header("If-Modified-Since", it) }
                    }
                }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { current.resolve(it) }
                        ?: throw FeedFetchException("リダイレクト先が正しくありません")
                    if (!isAllowedRedirect(current, next)) throw FeedFetchException("安全でないリダイレクトのため取得しませんでした")
                    current = next
                    return@repeat
                }
                if (response.code == HTTP_NOT_MODIFIED) return FeedResponse.NotModified
                if (!response.isSuccessful) throw FeedFetchException("取得できませんでした(HTTP ${response.code})")
                val body = response.body
                if (body.contentLength() > MAX_BODY_BYTES) throw FeedFetchException(TOO_LARGE_MESSAGE)
                val bytes = readCapped(body.byteStream())
                return FeedResponse.Body(
                    FeedBody(
                        bytes = bytes,
                        charsetName = body.contentType()?.charset()?.name(),
                        finalUrl = current.toString(),
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified")
                    )
                )
            }
        }
        throw FeedFetchException("リダイレクトが多すぎます")
    }

    /** [input] を [MAX_BODY_BYTES] まで読む。超えたら失敗 */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BODY_BYTES) throw FeedFetchException(TOO_LARGE_MESSAGE)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        /** フィード・ページの本文の大きさの上限 */
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val CALL_TIMEOUT_SECONDS = 30L
        private const val BUFFER_BYTES = 16 * 1024
        private const val HTTP_NOT_MODIFIED = 304
        private const val TOO_LARGE_MESSAGE = "フィードが大きすぎるため読み込みませんでした"
        private const val USER_AGENT = "Mozilla/5.0 (Android) EchoMark feed reader"
        private const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.5, */*;q=0.1"
    }
}

package com.unchunks.echomark.data.remote

import com.unchunks.echomark.di.DispatcherProvider
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/** URL先から取得したページ情報 */
data class FetchedContent(
    val title: String?,
    val description: String?,
    val text: String,
    /** OG 画像の絶対 URL(http/https のみ。無ければ null) */
    val imageUrl: String? = null,
    /** サイト名(og:site_name など。無ければ null) */
    val siteName: String? = null
)

/**
 * OkHttp + Jsoup で Web ページのタイトル・概要・本文を取得する。
 * ローカルネットワーク・ループバックのアドレスには接続しない([isPublicAddress])。リダイレクトは自前で追い、
 * 各段で http/https 以外と https → http の格下げを拒否する。
 */
@Singleton
class UrlContentFetcher internal constructor(
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
        .build()

    /** 失敗時は Result.failure(例外) を返す。呼び出し側はタイトル=URLのまま続行できる */
    suspend fun fetch(url: String): Result<FetchedContent> =
        withContext(dispatcherProvider.io) {
            runCatching { fetchBlocking(url) }
        }

    private fun fetchBlocking(url: String): FetchedContent {
        require(url.startsWith("http://") || url.startsWith("https://")) { "unsupported scheme: $url" }

        var current = url.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) {
            when (val step = fetchOnce(current)) {
                is FetchStep.Done -> return step.content
                is FetchStep.Redirect -> current = step.next
            }
        }
        throw IOException("too many redirects")
    }

    private sealed interface FetchStep {
        class Done(val content: FetchedContent) : FetchStep
        class Redirect(val next: HttpUrl) : FetchStep
    }

    /** 1回分の取得。リダイレクトなら次の URL を、そうでなければ取得した内容を返す。 */
    private fun fetchOnce(url: HttpUrl): FetchStep {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                val next = response.header("Location")?.let { url.resolve(it) }
                    ?: throw IOException("invalid redirect")
                if (!isAllowedRedirect(url, next)) throw IOException("redirect not allowed: ${url.scheme} -> ${next.scheme}")
                return FetchStep.Redirect(next)
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val mediaType = body.contentType()
            if (mediaType != null && mediaType.subtype != "html" && mediaType.subtype != "xhtml+xml") {
                throw IOException("unsupported content type: $mediaType")
            }

            val bytes = readCapped(body.byteStream())
            val document = Jsoup.parse(
                bytes.inputStream(),
                mediaType?.charset()?.name(),
                response.request.url.toString()
            )
            return FetchStep.Done(HtmlContentExtractor.extract(document))
        }
    }

    /** 上限サイズまでだけ読み込む(巨大レスポンス対策) */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < MAX_BODY_BYTES) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, minOf(read, MAX_BODY_BYTES - out.size()))
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) EchoMark/1.0"
    }
}

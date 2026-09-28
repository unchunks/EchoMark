package com.unchunks.echomark.data.remote

import com.unchunks.echomark.di.DispatcherProvider
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** URL先から取得したページ情報 */
data class FetchedContent(
    val title: String?,
    val description: String?,
    val text: String
)

/** OkHttp + Jsoup で Web ページのタイトル・概要・本文を取得する */
@Singleton
class UrlContentFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider
) {

    /** 失敗時は Result.failure(例外) を返す。呼び出し側はタイトル=URLのまま続行できる */
    suspend fun fetch(url: String): Result<FetchedContent> =
        withContext(dispatcherProvider.io) {
            runCatching { fetchBlocking(url) }
        }

    private fun fetchBlocking(url: String): FetchedContent {
        require(url.startsWith("http://") || url.startsWith("https://")) { "unsupported scheme: $url" }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
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
            return HtmlContentExtractor.extract(document)
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
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) EchoMark/1.0"
    }
}

package com.unchunks.echomark.data.discover

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** 各検索先のクライアントが共有する、通信と文字列の整形(純粋関数を除き、通信は呼び出し元のスレッドで行う)。 */
internal object SearchSupport {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) EchoMark/1.0"

    /** 応答を読む上限(YouTube の検索結果が約 1.3MB あるため余裕を持たせる) */
    const val MAX_RESPONSE_BYTES = 3 * 1024 * 1024

    /** 概要の上限文字数 */
    const val MAX_SNIPPET_CHARS = 300

    /** リクエストを実行して本文を文字列で返す。2xx 以外・上限超えは [IOException]。 */
    fun execute(client: OkHttpClient, request: Request): String =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val bytes = readCapped(response.body.byteStream(), MAX_RESPONSE_BYTES)
            String(bytes, response.body.contentType()?.charset() ?: Charsets.UTF_8)
        }

    /** 上限サイズまで読む。超えるものは途中で切れた XML・JSON になるので使わない */
    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) throw IOException("response too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** 連続する空白(改行・全角スペースを含む)を1つの半角スペースにして前後を削る */
    fun normalizeWhitespace(text: String): String =
        text.replace(WHITESPACE, " ").trim()

    /** HTML のタグと実体参照を取り除いたプレーンテキストにする */
    fun plainText(html: String): String =
        normalizeWhitespace(Jsoup.parse(html).text())

    /** 概要を [MAX_SNIPPET_CHARS] 文字までにする(空白は整える) */
    fun snippet(text: String): String =
        normalizeWhitespace(text).take(MAX_SNIPPET_CHARS)

    /** URL のホスト名(`www.` を除く)。http/https 以外・解析できないときは null。 */
    fun hostOf(url: String): String? {
        val host = runCatching { java.net.URI(url) }.getOrNull()
            ?.takeIf { it.scheme == "http" || it.scheme == "https" }
            ?.host
            ?.lowercase()
            ?: return null
        return host.removePrefix("www.").takeIf { it.isNotEmpty() }
    }

    /** http/https の URL だけを許す(javascript: などを候補にしない) */
    fun isWebUrl(url: String): Boolean = hostOf(url) != null

    /** 直下の子要素のうち、タグ名が [name](`dc:subject` のように接頭辞付き)のものの本文。無ければ null。 */
    fun childText(parent: Element, name: String): String? =
        parent.children().firstOrNull { it.tagName() == name }?.text()

    /** 直下の子要素のうち、タグ名が [name] のものすべて */
    fun children(parent: Element, name: String): List<Element> =
        parent.children().filter { it.tagName() == name }

    /** url と title が揃った候補だけを、URL の重複を除いて [limit] 件まで返す */
    fun finish(candidates: List<ResourceCandidate>, limit: Int): List<ResourceCandidate> =
        candidates
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
            .take(limit.coerceAtLeast(0))

    private val WHITESPACE = Regex("""[\s　]+""")
}

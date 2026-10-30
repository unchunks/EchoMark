package com.unchunks.echomark.data.remote

import com.unchunks.echomark.domain.bookmark.model.GitHubRepoRef
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * GitHub のリポジトリの情報と README を、GitHub の API(認証なし)から取る(#14)。
 * 認証なしでは 1 時間に 60 回までの上限があるため、上限・失敗・見つからないときは null を返し、
 * 呼び出し側はページの HTML から取った内容のまま続ける。
 *
 * [client] は、ローカルネットワークに接続しない設定の接続(UrlContentFetcher と同じ)。リダイレクトは追わない設定のため、
 * 名前を変えたリポジトリの API が返す 301 は、この中で 1 回だけ、同じ API のホストの中で追う。
 */
class GitHubRepoFetcher(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val apiBase: HttpUrl = DEFAULT_API_BASE.toHttpUrl()
) {
    /** 取得した内容。[title] はブックマークのタイトル、[text] は AI に渡す本文。 */
    data class Content(val title: String, val text: String)

    /** 失敗(上限・見つからない・通信エラー)なら null。 */
    fun fetch(repo: GitHubRepoRef): Content? {
        val info = get("repos/${repo.owner}/${repo.repo}", ACCEPT_JSON)?.let { GitHubRepoText.parseInfo(it) } ?: return null
        // README が無いリポジトリもある(404)。情報だけで本文を作る
        val readme = get("repos/${repo.owner}/${repo.repo}/readme", ACCEPT_RAW)
        return Content(title = GitHubRepoText.title(info), text = GitHubRepoText.build(info, readme))
    }

    private fun get(path: String, accept: String): String? {
        var url = apiBase.newBuilder().addEncodedPathSegments(path).build()
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", API_VERSION)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        val next = response.header("Location")?.let { url.resolve(it) } ?: return null
                        // API のホストの中だけ(別のホストへは行かない)
                        if (next.host != apiBase.host || next.scheme != apiBase.scheme) return null
                        url = next
                        return@use
                    }
                    if (!response.isSuccessful) return null
                    return String(readCapped(response.body.byteStream()).toByteArray(), Charsets.UTF_8)
                }
            } catch (_: IOException) {
                return null
            }
        }
        return null
    }

    private fun readCapped(input: InputStream): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (out.size() < MAX_BYTES) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, minOf(read, MAX_BYTES - out.size()))
        }
        return out
    }

    companion object {
        const val DEFAULT_API_BASE = "https://api.github.com/"
        private const val ACCEPT_JSON = "application/vnd.github+json"

        /** README を、Markdown のまま(JSON に包まずに)返させる */
        private const val ACCEPT_RAW = "application/vnd.github.raw+json"
        private const val API_VERSION = "2022-11-28"
        private const val MAX_REDIRECTS = 1
        private const val MAX_BYTES = 300 * 1024
        private const val BUFFER_BYTES = 8 * 1024
    }
}

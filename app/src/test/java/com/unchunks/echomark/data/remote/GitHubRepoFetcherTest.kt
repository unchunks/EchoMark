package com.unchunks.echomark.data.remote

import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.GitHubFileRef
import com.unchunks.echomark.domain.bookmark.model.GitHubRepoRef
import kotlinx.coroutines.Dispatchers
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** GitHub の API からリポジトリの情報と README を取る(MockWebServer。実際の GitHub には接続しない)。 */
class GitHubRepoFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: GitHubRepoFetcher

    private val repo = GitHubRepoRef("unchunks", "EchoMark")

    private val infoJson = """
        {"full_name":"unchunks/EchoMark","description":"ブックマーク整理アプリ","language":"Kotlin","topics":["android"],
         "license":{"spdx_id":"MIT"},"stargazers_count":10,"forks_count":2,"pushed_at":"2026-10-01T00:00:00Z","archived":false}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = GitHubRepoFetcher(OkHttpClient.Builder().followRedirects(false).build(), "EchoMark-Test", server.url("/"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun 情報とREADMEから題名と本文を作る() {
        server.enqueue(json(infoJson))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/plain").body("# EchoMark\n\n![logo](logo.png)\n整理します。").build())

        val content = fetcher.fetch(repo)!!

        assertEquals("unchunks/EchoMark: ブックマーク整理アプリ", content.title)
        assertTrue(content.text, content.text.startsWith("リポジトリ: unchunks/EchoMark\n説明: ブックマーク整理アプリ\n言語: Kotlin"))
        assertTrue(content.text, content.text.endsWith("README:\n# EchoMark\n\n整理します。"))
        val info = server.takeRequest()
        assertEquals("/repos/unchunks/EchoMark", info.url.encodedPath)
        assertEquals("EchoMark-Test", info.headers["User-Agent"])
        assertEquals("application/vnd.github+json", info.headers["Accept"])
        val readme = server.takeRequest()
        assertEquals("/repos/unchunks/EchoMark/readme", readme.url.encodedPath)
        assertEquals("application/vnd.github.raw+json", readme.headers["Accept"])
    }

    @Test
    fun READMEが無いリポジトリは情報だけの本文() {
        server.enqueue(json(infoJson))
        server.enqueue(json("""{"message":"Not Found"}""", code = 404))

        val content = fetcher.fetch(repo)!!

        assertTrue(content.text, content.text.startsWith("リポジトリ: unchunks/EchoMark"))
        assertTrue(content.text, !content.text.contains("README:"))
    }

    @Test
    fun 上限や見つからないときはnullでページの内容のままにできる() {
        server.enqueue(json("""{"message":"API rate limit exceeded"}""", code = 403))
        assertNull(fetcher.fetch(repo))

        server.enqueue(json("""{"message":"Not Found"}""", code = 404))
        assertNull(fetcher.fetch(repo))

        server.enqueue(json("<html>not json</html>"))
        assertNull(fetcher.fetch(repo))
    }

    @Test
    fun 名前を変えたリポジトリの301はAPIのホストの中で1回だけ追う() {
        server.enqueue(
            MockResponse.Builder().code(301).addHeader("Location", server.url("/repositories/42").toString()).build()
        )
        server.enqueue(json(infoJson))
        server.enqueue(json("""{"message":"Not Found"}""", code = 404))

        val content = fetcher.fetch(repo)!!

        assertTrue(content.title, content.title.startsWith("unchunks/EchoMark"))
        assertEquals("/repos/unchunks/EchoMark", server.takeRequest().url.encodedPath)
        assertEquals("/repositories/42", server.takeRequest().url.encodedPath)
    }

    @Test
    fun 別のホストへのリダイレクトは追わない() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://evil.example.com/steal").build())

        assertNull(fetcher.fetch(repo))
        assertEquals(1, server.requestCount)
    }

    // ---- UrlContentFetcher への組み込み ----

    private fun urlFetcher() = UrlContentFetcher(
        OkHttpClient(),
        TestDispatcherProvider(Dispatchers.Unconfined),
        githubApiEndpoint = server.url("/")
    ) { true }

    private val page = FetchedContent(
        title = "GitHub - unchunks/EchoMark: ...",
        description = "og の説明",
        text = "ファイル一覧 ナビゲーション",
        imageUrl = "https://opengraph.githubassets.com/x/unchunks/EchoMark",
        siteName = "GitHub"
    )

    @Test
    fun リポジトリのページは情報とREADMEで差し替え_画像とサイト名は残す() {
        server.enqueue(json(infoJson))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/plain").body("README の本文").build())

        val result = urlFetcher().withGitHubRepo("https://github.com/unchunks/EchoMark", page)

        assertEquals("unchunks/EchoMark: ブックマーク整理アプリ", result.title)
        assertNull(result.description)
        assertTrue(result.text, result.text.endsWith("README:\nREADME の本文"))
        assertEquals(page.imageUrl, result.imageUrl)
        assertEquals("GitHub", result.siteName)
    }

    @Test
    fun APIが失敗したらページから取った内容のまま() {
        server.enqueue(json("""{"message":"rate limit"}""", code = 403))

        assertEquals(page, urlFetcher().withGitHubRepo("https://github.com/unchunks/EchoMark", page))
    }

    @Test
    fun ディレクトリのページはそのディレクトリのREADMEを使う() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/plain").body("# アプリの本体").build())
        server.enqueue(json(infoJson))

        val result = urlFetcher().withGitHubRepo("https://github.com/unchunks/EchoMark/tree/main/app/src", page)

        assertEquals("unchunks/EchoMark/app/src", result.title)
        assertTrue(result.text, result.text.startsWith("リポジトリ: unchunks/EchoMark\nディレクトリ: app/src\n"))
        assertTrue(result.text, result.text.endsWith("README:\n# アプリの本体"))
        val readme = server.takeRequest()
        assertEquals("/repos/unchunks/EchoMark/readme/app/src", readme.url.encodedPath)
        assertEquals("main", readme.url.queryParameter("ref"))
    }

    @Test
    fun READMEの無いディレクトリはリポジトリのREADMEに置き換えずページのままにする() {
        server.enqueue(json("""{"message":"Not Found"}""", code = 404))

        val result = urlFetcher().withGitHubRepo("https://github.com/unchunks/EchoMark/tree/main/app/src", page)

        assertEquals(page, result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun リポジトリではないURLには何もしない() {
        assertEquals(page, urlFetcher().withGitHubRepo("https://github.com/unchunks/EchoMark/issues/1", page))
        assertEquals(page, urlFetcher().withGitHubRepo("https://example.com/", page))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun コードのファイルの中身に置き場所の見出しを付ける() {
        val file = GitHubFileRef(repo, "main", "app/src/Main.kt")

        val result = urlFetcher().withGitHubFile(file, FetchedContent("Main.kt", null, "fun main() {}"))

        assertEquals("Main.kt", result.title)
        assertEquals("リポジトリ: unchunks/EchoMark\nファイル: app/src/Main.kt\n\nfun main() {}", result.text)
        assertEquals("GitHub", result.siteName)
    }

    @Test
    fun 中身が空のファイルは失敗にして通常のページとして取り直す() {
        val file = GitHubFileRef(repo, "main", "Empty.kt")

        val failure = runCatching { urlFetcher().withGitHubFile(file, FetchedContent("Empty.kt", null, "")) }

        assertTrue(failure.isFailure)
    }

    // ---- テキストのファイル ----

    @Test
    fun テキストのファイルはそのまま本文にしてファイル名をタイトルにする() = kotlinx.coroutines.runBlocking {
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/plain; charset=utf-8").body("fun main() {\n    println(\"こんにちは\")\n}\n").build()
        )

        val content = urlFetcher().fetch(server.url("/src/Main.kt").toString()).getOrThrow()

        assertEquals("Main.kt", content.title)
        assertEquals("fun main() {\n    println(\"こんにちは\")\n}", content.text)
        assertNull(content.description)
        assertNull(content.file)
    }

    @Test
    fun HTMLでもテキストでもない応答は今までどおり失敗() = kotlinx.coroutines.runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("{}").build())

        assertTrue(urlFetcher().fetch(server.url("/data.json").toString()).isFailure)
    }
}

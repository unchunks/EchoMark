package com.unchunks.echomark.data.remote

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlContentExtractorTest {

    private fun extract(html: String) = HtmlContentExtractor.extract(Jsoup.parse(html))

    @Test
    fun ogタイトルと説明をtitleやmetaより優先する() {
        val result = extract(
            """
            <html><head>
              <title>ページタイトル</title>
              <meta property="og:title" content="OGタイトル">
              <meta property="og:description" content="OG説明">
              <meta name="description" content="通常の説明">
            </head><body><p>本文</p></body></html>
            """.trimIndent()
        )
        assertEquals("OGタイトル", result.title)
        assertEquals("OG説明", result.description)
    }

    @Test
    fun ogが無ければtitleとmeta_descriptionを使う() {
        val result = extract(
            """
            <html><head>
              <title>  ページタイトル  </title>
              <meta name="description" content=" 通常の説明 ">
            </head><body>本文</body></html>
            """.trimIndent()
        )
        assertEquals("ページタイトル", result.title)
        assertEquals("通常の説明", result.description)
    }

    @Test
    fun タイトルも説明も無ければnull() {
        val result = extract("<html><body><p>本文だけ</p></body></html>")
        assertNull(result.title)
        assertNull(result.description)
        assertEquals("本文だけ", result.text)
    }

    @Test
    fun 空白だけのmetaは無視してフォールバックする() {
        val result = extract(
            """
            <html><head><title>T</title><meta property="og:title" content="  "></head><body></body></html>
            """.trimIndent()
        )
        assertEquals("T", result.title)
    }

    @Test
    fun ノイズ要素は本文から除去される() {
        val result = extract(
            """
            <html><body>
              <header>ヘッダー</header>
              <nav>ナビ</nav>
              <script>var x = 1;</script>
              <style>.a { color: red }</style>
              <p>本文です</p>
              <aside>広告</aside>
              <footer>フッター</footer>
            </body></html>
            """.trimIndent()
        )
        assertEquals("本文です", result.text)
    }

    @Test
    fun articleがあればその中だけを本文にする() {
        val result = extract(
            """
            <html><body>
              <div>周辺テキスト</div>
              <main><p>mainの本文</p></main>
              <article><p>articleの本文</p></article>
            </body></html>
            """.trimIndent()
        )
        assertEquals("articleの本文", result.text)
    }

    @Test
    fun articleが無ければmainを使う() {
        val result = extract(
            """
            <html><body><div>周辺</div><main><p>mainの本文</p></main></body></html>
            """.trimIndent()
        )
        assertEquals("mainの本文", result.text)
    }

    @Test
    fun 本文は最大文字数で切り詰められる() {
        val long = "あ".repeat(HtmlContentExtractor.MAX_TEXT_CHARS + 100)
        val result = extract("<html><body><p>$long</p></body></html>")
        assertEquals(HtmlContentExtractor.MAX_TEXT_CHARS, result.text.length)
    }

    @Test
    fun 空のドキュメントでも落ちない() {
        val result = extract("")
        assertNull(result.title)
        assertNull(result.description)
        assertTrue(result.text.isEmpty())
        assertFalse(result.text.contains("null"))
    }

    @Test
    fun og画像とサイト名を取り出す() {
        val result = extract(
            """
            <html><head>
              <meta property="og:image" content="https://cdn.example.com/og.png">
              <meta property="og:site_name" content=" Example Blog ">
            </head><body></body></html>
            """.trimIndent()
        )
        assertEquals("https://cdn.example.com/og.png", result.imageUrl)
        assertEquals("Example Blog", result.siteName)
    }

    @Test
    fun 相対URLのog画像はページのURLを基準に絶対化する() {
        val document = Jsoup.parse(
            """<html><head><meta property="og:image" content="/img/cover.jpg"></head></html>""",
            "https://www.example.com/articles/1"
        )
        val result = HtmlContentExtractor.extract(document)
        assertEquals("https://www.example.com/img/cover.jpg", result.imageUrl)
    }

    @Test
    fun og画像が無ければtwitter画像を使いsecure_urlを優先する() {
        val twitter = extract(
            """<html><head><meta name="twitter:image" content="https://example.com/tw.png"></head></html>"""
        )
        assertEquals("https://example.com/tw.png", twitter.imageUrl)

        val secure = extract(
            """
            <html><head>
              <meta property="og:image" content="http://example.com/a.png">
              <meta property="og:image:secure_url" content="https://example.com/a.png">
            </head></html>
            """.trimIndent()
        )
        assertEquals("https://example.com/a.png", secure.imageUrl)
    }

    @Test
    fun 基準の無い相対URLやdata画像は使わずサイト名はapplication_nameで補う() {
        val result = extract(
            """
            <html><head>
              <meta property="og:image" content="data:image/png;base64,AAAA">
              <meta name="application-name" content="アプリ名">
            </head></html>
            """.trimIndent()
        )
        assertNull(result.imageUrl)
        assertEquals("アプリ名", result.siteName)

        val relative = extract("""<html><head><meta property="og:image" content="img/a.png"></head></html>""")
        assertNull(relative.imageUrl)
    }

    @Test
    fun og画像もサイト名も無ければnull() {
        val result = extract("<html><head><title>T</title></head><body>本文</body></html>")
        assertNull(result.imageUrl)
        assertNull(result.siteName)
    }

    // 実際の m.youtube.com の構造を真似た最小の HTML。先に `ytInitialPlayerResponse = null;` の script がある
    private fun youtubeHtml(playerResponse: String, siteName: String = "YouTube") =
        """
        <html><head>
          <title>YouTube</title>
          <meta property="og:site_name" content="$siteName">
          <meta property="og:title" content="サンプル動画">
          <meta name="description" content="作成した動画を友だち、家族、世界中の人たちと共有">
        </head><body>
          <script>var ytInitialPlayerResponse = null;//# sourceURL=yt-mweb-initial-player-response.js</script>
          <script>var ytInitialPlayerResponse = $playerResponse;var meta = document.createElement('meta');</script>
        </body></html>
        """.trimIndent()

    private val youtubePlayerResponse = """
        {"responseContext":{"visitorData":"x;y"},"videoDetails":{"videoId":"abc","title":"動画の題名",
        "keywords":["音楽","live"],"channelId":"UC1","shortDescription":"1行目 {波括弧} と \"引用\"\n\n2行目","author":"チャンネル名"}}
    """.trimIndent().replace("\n", "")

    @Test
    fun YouTubeはplayerResponseから説明文とチャンネル名を取り出す() {
        val document = Jsoup.parse(youtubeHtml(youtubePlayerResponse), "https://m.youtube.com/watch?v=abc")
        val result = HtmlContentExtractor.extract(document)
        assertEquals("サンプル動画", result.title)
        assertEquals(
            "チャンネル: チャンネル名\n\n1行目 {波括弧} と \"引用\"\n\n2行目\n\nキーワード: 音楽, live",
            result.text
        )
        // 説明文が text に入るので、汎用の meta description は重複させない
        assertNull(result.description)
    }

    @Test
    fun YouTubeはホスト名が不明でもサイト名で判定する() {
        val result = extract(youtubeHtml(youtubePlayerResponse))
        assertTrue(result.text.startsWith("チャンネル: チャンネル名"))
        assertNull(result.description)
    }

    @Test
    fun YouTubeの本文は最大文字数で切り詰められる() {
        val long = "あ".repeat(HtmlContentExtractor.MAX_TEXT_CHARS + 100)
        val json = """{"videoDetails":{"title":"t","author":"a","shortDescription":"$long","keywords":["k"]}}"""
        val result = extract(youtubeHtml(json))
        assertEquals(HtmlContentExtractor.MAX_TEXT_CHARS, result.text.length)
        assertTrue(result.text.startsWith("チャンネル: a"))
    }

    @Test
    fun YouTubeでJSONが壊れていれば汎用の抽出にフォールバックする() {
        val document = Jsoup.parse(
            youtubeHtml("""{"videoDetails":{"title":"途中で切れ"""),
            "https://www.youtube.com/watch?v=abc"
        )
        val result = HtmlContentExtractor.extract(document)
        assertEquals("サンプル動画", result.title)
        assertEquals("作成した動画を友だち、家族、世界中の人たちと共有", result.description)
        assertTrue(result.text.isEmpty())
    }

    @Test
    fun YouTubeでvideoDetailsが無ければ汎用の抽出にフォールバックする() {
        val result = extract(youtubeHtml("""{"responseContext":{}}"""))
        assertEquals("作成した動画を友だち、家族、世界中の人たちと共有", result.description)
        assertTrue(result.text.isEmpty())
    }

    @Test
    fun YouTube以外のページではplayerResponseがあっても無視する() {
        val html = youtubeHtml(youtubePlayerResponse, siteName = "Example Blog")
        val document = Jsoup.parse(html, "https://blog.example.com/post")
        val result = HtmlContentExtractor.extract(document)
        assertEquals("作成した動画を友だち、家族、世界中の人たちと共有", result.description)
        assertTrue(result.text.isEmpty())
    }

    @Test
    fun YouTubeは字幕の取得に使う動画IDと本文の材料を返す() {
        val document = Jsoup.parse(youtubeHtml(youtubePlayerResponse), "https://m.youtube.com/watch?v=abc")
        val video = HtmlContentExtractor.extract(document).youtube
        assertEquals("abc", video?.videoId)
        assertEquals("チャンネル名", video?.author)
        assertEquals(listOf("音楽", "live"), video?.keywords)
    }

    @Test
    fun YouTube以外のページでは動画情報を返さない() {
        val document = Jsoup.parse(youtubeHtml(youtubePlayerResponse, siteName = "Example Blog"), "https://blog.example.com/post")
        assertNull(HtmlContentExtractor.extract(document).youtube)
    }

    private val video = YouTubeVideo(
        videoId = "abc",
        title = "題名",
        author = "チャンネル名",
        description = "説明文",
        keywords = listOf("音楽")
    )

    @Test
    fun 字幕があればチャンネル名と説明文のあとに字幕を入れる() {
        val text = HtmlContentExtractor.youtubeText(video, CaptionTranscript("1行目\n2行目", "ja", isAutoGenerated = false))
        assertEquals("チャンネル: チャンネル名\n\n説明文\n\n字幕:\n1行目\n2行目\n\nキーワード: 音楽", text)
    }

    @Test
    fun 自動生成の字幕はそうと分かるように書く() {
        val text = HtmlContentExtractor.youtubeText(video, CaptionTranscript("話した内容", "ja", isAutoGenerated = true))
        assertTrue(text!!.contains("字幕(自動生成):\n話した内容"))
    }

    @Test
    fun 字幕があるときは長い説明文を短くし本文の上限を広げる() {
        val longDescription = "説".repeat(HtmlContentExtractor.MAX_DESCRIPTION_WITH_TRANSCRIPT_CHARS + 500)
        val transcript = "字".repeat(YouTubeCaptions.MAX_TRANSCRIPT_CHARS)
        val text = HtmlContentExtractor.youtubeText(
            video.copy(description = longDescription),
            CaptionTranscript(transcript, "ja", isAutoGenerated = false)
        )!!

        val description = text.lines()[2]
        assertEquals(HtmlContentExtractor.MAX_DESCRIPTION_WITH_TRANSCRIPT_CHARS, description.length)
        assertTrue(description.endsWith("…"))
        assertTrue(text.length in (HtmlContentExtractor.MAX_TEXT_CHARS + 1)..HtmlContentExtractor.MAX_TEXT_WITH_TRANSCRIPT_CHARS)
        assertTrue(text.contains(transcript))
    }

    @Test
    fun 説明文もチャンネル名も無くても字幕があれば本文を作る() {
        val text = HtmlContentExtractor.youtubeText(
            video.copy(author = null, description = null, keywords = emptyList()),
            CaptionTranscript("話した内容", "en", isAutoGenerated = true)
        )
        assertEquals("字幕(自動生成):\n話した内容", text)
        assertNull(HtmlContentExtractor.youtubeText(video.copy(author = null, description = null), transcript = null))
    }
}

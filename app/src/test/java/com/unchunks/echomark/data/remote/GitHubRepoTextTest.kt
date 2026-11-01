package com.unchunks.echomark.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepoTextTest {

    private val json = """
        {"full_name":"unchunks/EchoMark","description":"AI でブックマークを整理するアプリ","language":"Kotlin",
         "topics":["android","bookmark","ai"],"license":{"spdx_id":"MIT","name":"MIT License"},
         "stargazers_count":1234,"forks_count":56,"pushed_at":"2026-10-01T12:34:56Z","archived":false,
         "homepage":"https://example.com/echomark"}
    """.trimIndent()

    @Test
    fun APIの応答から情報を取り出す() {
        val info = GitHubRepoText.parseInfo(json)!!

        assertEquals("unchunks/EchoMark", info.fullName)
        assertEquals("AI でブックマークを整理するアプリ", info.description)
        assertEquals("Kotlin", info.language)
        assertEquals(listOf("android", "bookmark", "ai"), info.topics)
        assertEquals("MIT", info.license)
        assertEquals(1234, info.stars)
        assertEquals(56, info.forks)
        assertEquals("2026-10-01", info.pushedOn)
        assertEquals("https://example.com/echomark", info.homepage)
    }

    @Test
    fun 無い項目_null_判定できないライセンスは載せない() {
        val info = GitHubRepoText.parseInfo(
            """{"full_name":"a/b","description":null,"language":null,"license":{"spdx_id":"NOASSERTION"},"homepage":""}"""
        )!!

        assertNull(info.description)
        assertNull(info.language)
        assertNull(info.license)
        assertNull(info.homepage)
        assertEquals(emptyList<String>(), info.topics)
    }

    @Test
    fun JSONでない応答や名前の無い応答は読めない() {
        assertNull(GitHubRepoText.parseInfo("<html>not json</html>"))
        assertNull(GitHubRepoText.parseInfo("""{"message":"Not Found"}"""))
    }

    @Test
    fun タイトルはオーナーとリポジトリ名と説明_長ければ切る() {
        val info = GitHubRepoText.parseInfo(json)!!

        assertEquals("unchunks/EchoMark: AI でブックマークを整理するアプリ", GitHubRepoText.title(info))
        assertEquals("unchunks/EchoMark", GitHubRepoText.title(info.copy(description = null)))
        val long = GitHubRepoText.title(info.copy(description = "あ".repeat(200)))
        assertEquals(80, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun 本文は見出しつきの情報とREADME() {
        val info = GitHubRepoText.parseInfo(json)!!

        val text = GitHubRepoText.build(info, "# EchoMark\n\nブックマークを AI で整理します。")

        assertEquals(
            """
            リポジトリ: unchunks/EchoMark
            説明: AI でブックマークを整理するアプリ
            言語: Kotlin
            トピック: android, bookmark, ai
            ライセンス: MIT
            スター: 1,234 ・ フォーク: 56
            最終更新: 2026-10-01
            公式サイト: https://example.com/echomark

            README:
            # EchoMark

            ブックマークを AI で整理します。
            """.trimIndent(),
            text
        )
    }

    @Test
    fun アーカイブ済みはその旨を書き_READMEが無ければ情報だけ() {
        val info = GitHubRepoText.parseInfo(json)!!.copy(archived = true, homepage = null)

        val text = GitHubRepoText.build(info, readme = null)

        assertTrue(text, text.contains("状態: アーカイブ済み(開発は終わっています)"))
        assertFalse(text, text.contains("README:"))
        assertFalse(GitHubRepoText.build(info, readme = "  \n ").contains("README:"))
    }

    @Test
    fun 本文は上限に収める() {
        val info = GitHubRepoText.parseInfo(json)!!

        val text = GitHubRepoText.build(info, "あ".repeat(20_000))

        assertEquals(GitHubRepoText.MAX_TEXT_CHARS, text.length)
        assertTrue(text.startsWith("リポジトリ: unchunks/EchoMark"))
    }

    @Test
    fun READMEからバッジ_画像_HTML_リンクの記法を除く() {
        val readme = """
            <!-- badges -->
            [![Build](https://img.shields.io/badge/build-passing.svg)](https://ci.example.com)
            ![screenshot](docs/screen.png)
            <p align="center"><img src="logo.png"></p>

            # EchoMark

            詳しくは [ドキュメント](https://example.com/docs) と [FAQ][faq] を見てください。

            [faq]: https://example.com/faq
        """.trimIndent()

        val cleaned = GitHubRepoText.cleanReadme(readme)

        assertEquals("# EchoMark\n\n詳しくは ドキュメント と FAQ を見てください。", cleaned)
    }

    @Test
    fun コードブロックの中は手を付けない() {
        val readme = "## 導入\n\n```bash\n./gradlew assembleDebug  # <debug>\n![not an image](x)\n```\n\n[リンク](https://example.com)"

        val cleaned = GitHubRepoText.cleanReadme(readme)

        assertEquals("## 導入\n\n```bash\n./gradlew assembleDebug  # <debug>\n![not an image](x)\n```\n\nリンク", cleaned)
    }
}

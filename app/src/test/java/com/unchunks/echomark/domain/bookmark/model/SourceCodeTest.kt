package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub の URL の読み取りと、ソースコードの見分け方。 */
class SourceCodeTest {

    @Test
    fun リポジトリのページからオーナーとリポジトリ名を取る() {
        assertEquals(GitHubRepoRef("unchunks", "EchoMark"), GitHubUrls.repoOf("https://github.com/unchunks/EchoMark"))
        assertEquals(GitHubRepoRef("unchunks", "EchoMark"), GitHubUrls.repoOf("https://www.github.com/unchunks/EchoMark/"))
        assertEquals(GitHubRepoRef("unchunks", "EchoMark"), GitHubUrls.repoOf("https://github.com/unchunks/EchoMark.git"))
        assertEquals(GitHubRepoRef("unchunks", "EchoMark"), GitHubUrls.repoOf("https://github.com/unchunks/EchoMark?tab=readme-ov-file#install"))
        // リポジトリの中のディレクトリは、そのリポジトリとして扱う
        assertEquals("unchunks/EchoMark", GitHubUrls.repoOf("https://github.com/unchunks/EchoMark/tree/main/app")?.fullName)
    }

    @Test
    fun リポジトリではないGitHubのページは対象外() {
        assertNull(GitHubUrls.repoOf("https://github.com/unchunks"))
        assertNull(GitHubUrls.repoOf("https://github.com/settings/profile"))
        assertNull(GitHubUrls.repoOf("https://github.com/topics/kotlin"))
        assertNull(GitHubUrls.repoOf("https://github.com/unchunks/EchoMark/issues/1"))
        assertNull(GitHubUrls.repoOf("https://github.com/unchunks/EchoMark/pull/2"))
        assertNull(GitHubUrls.repoOf("https://github.com/unchunks/EchoMark/blob/main/README.md"))
        assertNull(GitHubUrls.repoOf("https://gitlab.com/unchunks/EchoMark"))
        assertNull(GitHubUrls.repoOf("https://example.com/unchunks/EchoMark"))
        assertNull(GitHubUrls.repoOf(null))
        assertNull(GitHubUrls.repoOf("not a url"))
    }

    @Test
    fun コードのファイルのページから中身を返すURLを作る() {
        val file = GitHubUrls.codeFileOf("https://github.com/unchunks/EchoMark/blob/main/app/src/Main.kt#L10")!!

        assertEquals(GitHubRepoRef("unchunks", "EchoMark"), file.repo)
        assertEquals("main", file.ref)
        assertEquals("app/src/Main.kt", file.path)
        assertEquals("Main.kt", file.fileName)
        assertEquals("https://raw.githubusercontent.com/unchunks/EchoMark/main/app/src/Main.kt", file.rawUrl)
    }

    @Test
    fun コード以外のファイルや深さの足りないURLは対象外() {
        assertNull(GitHubUrls.codeFileOf("https://github.com/unchunks/EchoMark/blob/main/README.md"))
        assertNull(GitHubUrls.codeFileOf("https://github.com/unchunks/EchoMark/blob/main"))
        assertNull(GitHubUrls.codeFileOf("https://github.com/unchunks/EchoMark/tree/main/app/Main.kt"))
        assertNull(GitHubUrls.codeFileOf("https://example.com/unchunks/EchoMark/blob/main/Main.kt"))
    }

    @Test
    fun ファイル名とMIMEタイプからソースコードか見分ける() {
        assertTrue(isSourceCodeFile("Main.kt", null))
        assertTrue(isSourceCodeFile("app/src/script.PY", null))
        assertTrue(isSourceCodeFile("Dockerfile", null))
        assertTrue(isSourceCodeFile(null, "text/x-kotlin"))
        assertTrue(isSourceCodeFile("memo", "application/x-sh"))
        assertFalse(isSourceCodeFile("memo.txt", "text/plain"))
        assertFalse(isSourceCodeFile("README.md", "text/x-markdown"))
        assertFalse(isSourceCodeFile("report.pdf", null))
        assertFalse(isSourceCodeFile(null, null))
    }

    @Test
    fun URLでは拡張子だけで決められないものと生のコードのホスト() {
        assertTrue(isSourceCodeUrl("https://raw.githubusercontent.com/o/r/main/anything"))
        assertTrue(isSourceCodeUrl("https://example.com/static/app.js?v=3"))
        // .php・.c などは Web ページのことが多い
        assertFalse(isSourceCodeUrl("https://example.com/index.php"))
        assertFalse(isSourceCodeUrl("https://example.com/article.html"))
        assertFalse(isSourceCodeUrl("https://example.com/"))
    }

    @Test
    fun コードに見える本文は過半数の行がコードの形() {
        val kotlin = """
            package com.example

            import kotlinx.coroutines.delay

            class Counter {
                private var value = 0
                fun increment() {
                    value++
                }
                fun get(): Int {
                    return value
                }
            }
        """.trimIndent()
        val python = "import os\n\ndef main():\n    for name in os.listdir('.'):\n        print(name)\n\nif __name__ == '__main__':\n    main()\n"

        assertTrue(looksLikeSourceCode(kotlin))
        assertTrue(looksLikeSourceCode(python))
    }

    @Test
    fun 文章のメモや短い断片はコードにしない() {
        val prose = "今日は会議があった。\n結論は来週に持ち越し。\n宿題は資料の作成。\n次回は金曜日に集まる。\n議事録は共有済み。\n以上。"
        val short = "val x = 1;\nval y = 2;\nval z = 3;"
        val english = "Meeting notes for today.\nWe talked about the roadmap.\nNobody objected to the new schedule.\nFollow up next week.\nThanks everyone."

        assertFalse(looksLikeSourceCode(prose))
        assertFalse(looksLikeSourceCode(short))
        assertFalse(looksLikeSourceCode(english))
        assertFalse(looksLikeSourceCode(""))
    }
}

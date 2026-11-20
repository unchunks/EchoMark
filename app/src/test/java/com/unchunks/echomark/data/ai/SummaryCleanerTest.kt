package com.unchunks.echomark.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 要約の書き出しに写されたタイトル・チャンネル名・前置きを除く。 */
class SummaryCleanerTest {

    private val title = "【初心者向け】Kotlin コルーチン入門 - YouTube"

    @Test
    fun この動画ではの前置きを除く() {
        assertEquals(
            "コルーチンで非同期処理を書く方法を、launch と async の違いから説明している。",
            SummaryCleaner.clean("この動画では、コルーチンで非同期処理を書く方法を、launch と async の違いから説明している。", title, null)
        )
    }

    @Test
    fun 書き出しのタイトルを除く() {
        assertEquals(
            "launch と async の違いと、構造化された並行性の考え方を説明している。",
            SummaryCleaner.clean("「【初心者向け】Kotlin コルーチン入門」では、launch と async の違いと、構造化された並行性の考え方を説明している。", title, null)
        )
    }

    @Test
    fun チャンネル名の動画での書き出しを除く() {
        assertEquals(
            "コルーチンのキャンセルと例外の扱いを、実際のコードで説明している。",
            SummaryCleaner.clean("Android開発チャンネルの動画で、コルーチンのキャンセルと例外の扱いを、実際のコードで説明している。", title, "Android開発チャンネル")
        )
        assertEquals(
            "コルーチンのキャンセルと例外の扱いを、実際のコードで説明している。",
            SummaryCleaner.clean("たろうさんが、コルーチンのキャンセルと例外の扱いを、実際のコードで説明している。", title, "たろう")
        )
    }

    @Test
    fun 前置きとタイトルが重なっても除く() {
        assertEquals(
            "非同期処理の基本を説明している。サンプルコード付き。",
            SummaryCleaner.clean("この動画は、【初心者向け】Kotlin コルーチン入門。非同期処理の基本を説明している。サンプルコード付き。", title, null)
        )
    }

    @Test
    fun 投稿者が話の主語の書き出しは残す() {
        val summary = "Google が Android 17 の新機能を発表し、通知の仕組みが変わることを説明している。"

        assertEquals(summary, SummaryCleaner.clean(summary, "Android 17 の新機能まとめ", "Google"))
    }

    @Test
    fun 短いタイトルは主語として残す() {
        val summary = "Kotlinは、JetBrains が開発した静的型付けのプログラミング言語。"

        assertEquals(summary, SummaryCleaner.clean(summary, "Kotlin", null))
    }

    @Test
    fun タイトルが文の一部になっていれば残す() {
        val summary = "【初心者向け】Kotlin コルーチン入門の内容を、要点だけ短く振り返る。"

        assertEquals(summary, SummaryCleaner.clean(summary, title, null))
    }

    @Test
    fun この記事の要点はのように助詞が続かなければ残す() {
        val summary = "この記事の要点は、ビルドを速くする設定が3つあること。"

        assertEquals(summary, SummaryCleaner.clean(summary, "Gradle を速くする", null))
    }

    @Test
    fun 除くとほとんど残らないときは元のまま() {
        assertEquals("この動画では、雑談。", SummaryCleaner.clean("この動画では、雑談。", title, null))
    }

    @Test
    fun 括弧で添えたチャンネル名は文の途中でも除く() {
        assertEquals(
            "コルーチンの基本を、実際のコードで説明している。",
            SummaryCleaner.clean("コルーチンの基本を(Android開発チャンネル)、実際のコードで説明している。", title, "Android開発チャンネル")
        )
    }

    @Test
    fun 本文のチャンネルの行から投稿者を取り出す() {
        assertEquals("Android開発チャンネル", SummaryCleaner.channelOf("チャンネル: Android開発チャンネル\n\n説明文"))
        assertNull(SummaryCleaner.channelOf("説明文だけ"))
    }
}

package com.unchunks.echomark.ui.bookmark

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardContentTest {

    @Test
    fun URLだけの文字列はURLとして扱う() {
        assertEquals(ClipboardContent.Url("https://example.com/a?x=1"), classifyClipboardText(" https://example.com/a?x=1\n"))
    }

    @Test
    fun スキームの無いドメインはURLとして扱う() {
        assertEquals(ClipboardContent.Url("https://example.com/a"), classifyClipboardText("example.com/a"))
    }

    @Test
    fun 短い題名とURLはURLとして扱う() {
        assertEquals(ClipboardContent.Url("https://example.com/a", "面白い記事"), classifyClipboardText("面白い記事 https://example.com/a"))
    }

    @Test
    fun 長い文章の中のURLはURLではなくテキストとして扱う() {
        val long = "あ".repeat(120) + " https://example.com/a"

        assertEquals(ClipboardContent.Text(long), classifyClipboardText(long))
    }

    @Test
    fun URLではない文字列はテキストとして前後の空白を除いて扱う() {
        assertEquals(ClipboardContent.Text("買うもの\n牛乳"), classifyClipboardText("  買うもの\n牛乳\n"))
    }

    @Test
    fun 空や空白だけは何も無いとして扱う() {
        assertEquals(ClipboardContent.Empty, classifyClipboardText(""))
        assertEquals(ClipboardContent.Empty, classifyClipboardText(" \n\t "))
    }

    @Test
    fun 画像の説明なら画像の提案() {
        assertEquals(ClipboardHint.IMAGE, clipboardHintOf(listOf("image/png"), null))
        assertEquals(ClipboardHint.IMAGE, clipboardHintOf(listOf("text/plain", "image/jpeg"), 0.9f))
    }

    @Test
    fun テキストの説明はURLの自信が高ければURL低ければテキストの提案() {
        assertEquals(ClipboardHint.URL, clipboardHintOf(listOf("text/plain"), 0.8f))
        assertEquals(ClipboardHint.TEXT, clipboardHintOf(listOf("text/plain"), 0.1f))
        assertEquals(ClipboardHint.TEXT, clipboardHintOf(listOf("text/html"), null))
        assertEquals(ClipboardHint.TEXT, clipboardHintOf(listOf("text/uri-list"), null))
    }

    @Test
    fun 保存できない種類や空の説明は提案しない() {
        assertEquals(ClipboardHint.NONE, clipboardHintOf(emptyList(), null))
        assertEquals(ClipboardHint.NONE, clipboardHintOf(listOf("application/x-custom"), null))
        assertEquals(ClipboardHint.NONE, clipboardHintOf(listOf("not-a-mime"), null))
    }

    @Test
    fun URLだけが何行も並んだ文字列はリンクの一覧としてテキストで扱う() {
        val list = "https://example.com/a\nhttps://example.com/b"

        assertEquals(ClipboardContent.Text(list), classifyClipboardText(list))
    }
}

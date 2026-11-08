package com.unchunks.echomark.domain.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractedBodyTest {

    private fun content(text: String, source: ExtractionSource = ExtractionSource.IMAGE, truncated: Boolean = false) =
        ExtractedContent.of(text, source, truncated = truncated)!!

    @Test
    fun メモが無ければ区切りと取り出したテキストだけ() {
        val body = ExtractedBody.merge(null, content("読み取った文字"))

        assertEquals("--- 画像の内容 ---\n読み取った文字", body)
    }

    @Test
    fun メモを残して後ろに区切りを付けて書く() {
        val body = ExtractedBody.merge("あとで読む", content("本文", ExtractionSource.PDF_TEXT))

        assertEquals("あとで読む\n\n--- PDF の本文 ---\n本文", body)
    }

    @Test
    fun 取り出し直すと前回の結果を置き換え_二重に追記しない() {
        val first = ExtractedBody.merge("メモ", content("一回目", ExtractionSource.PDF_TEXT))
        // 前回と見出しが違っても(テキスト抽出 → OCR)置き換える
        val second = ExtractedBody.merge(first, content("二回目", ExtractionSource.PDF_OCR))

        assertEquals("メモ\n\n--- PDF から読み取った文字 ---\n二回目", second)
        assertEquals(second, ExtractedBody.merge(second, content("二回目", ExtractionSource.PDF_OCR)))
    }

    @Test
    fun 分割するとメモと区画に分かれる() {
        val body = ExtractedBody.merge("一行目\n二行目", content("文字起こしの本文\n続き", ExtractionSource.TRANSCRIPT))

        val parts = ExtractedBody.parse(body)

        assertEquals("一行目\n二行目", parts.memo)
        assertEquals(listOf(ExtractedBody.Section(ExtractionSource.TRANSCRIPT, "文字起こしの本文\n続き")), parts.sections)
    }

    @Test
    fun 見出しに無い区切りのような行はメモの一部() {
        val memo = "--- 自分で書いた線 ---\nメモ"

        val parts = ExtractedBody.parse(memo)

        assertEquals(memo, parts.memo)
        assertTrue(parts.sections.isEmpty())
        assertEquals("$memo\n\n--- 文字起こし ---\nx", ExtractedBody.merge(memo, content("x", ExtractionSource.TRANSCRIPT)))
    }

    @Test
    fun 空の本文はメモ無し区画無し() {
        val parts = ExtractedBody.parse("  ")

        assertNull(parts.memo)
        assertTrue(parts.sections.isEmpty())
        assertNull(ExtractedBody.memoOf(null))
    }

    @Test
    fun 切り詰めたときは注記を添える() {
        val body = ExtractedBody.merge(null, content("本文", truncated = true))

        assertTrue(body.endsWith("\n\n${ExtractedBody.TRUNCATED_NOTE}"))
        // 注記は区画の本文に含まれるが、メモには混ざらない
        assertNull(ExtractedBody.parse(body).memo)
    }

    @Test
    fun CRLFの本文も分けられる() {
        val parts = ExtractedBody.parse("メモ\r\n\r\n--- 文字起こし ---\r\n本文")

        assertEquals("メモ", parts.memo)
        assertEquals("本文", parts.sections.single().text)
    }

    @Test
    fun ExtractedContentは改行を整えて上限で切り詰める() {
        val long = "あ".repeat(ExtractedContent.MAX_TEXT_LENGTH + 10)

        val cut = ExtractedContent.of(long, ExtractionSource.TEXT_FILE)!!
        val short = ExtractedContent.of("a  \r\n\r\n\r\n\r\nb\u0000c", ExtractionSource.TEXT_FILE)!!

        assertEquals(ExtractedContent.MAX_TEXT_LENGTH, cut.text.length)
        assertTrue(cut.truncated)
        assertEquals("a\n\nb c", short.text)
        assertFalse(short.truncated)
        assertNull(ExtractedContent.of(" \n ", ExtractionSource.TEXT_FILE))
        assertEquals("文字起こし", ExtractionSource.TRANSCRIPT.label)
        assertEquals(ExtractionSource.TRANSCRIPT, ExtractionSource.ofLabel("文字起こし"))
    }
}

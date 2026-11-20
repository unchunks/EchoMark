package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingModeTest {

    private fun paragraphs(vararg text: String) = text.map { ReadingBlock.Paragraph(it) }

    @Test
    fun 本文が無ければ空() {
        assertEquals(emptyList<ReadingBlock>(), ReadingBlocks.from(null))
        assertEquals(emptyList<ReadingBlock>(), ReadingBlocks.from("  \n\n "))
    }

    @Test
    fun 空の行以外の行を1つずつの段落にする() {
        val blocks = ReadingBlocks.from("最初の段落です。\n\n  次の段落です。  \r\n三つ目\n\n\n")

        assertEquals(paragraphs("最初の段落です。", "次の段落です。", "三つ目"), blocks)
    }

    @Test
    fun ファイルから取り出した文字があればメモと取り出した区画に見出しを付ける() {
        val content = "自分のメモ\n\n--- 文字起こし ---\n1行目\n2行目\n\n--- 画像の内容 ---\n文字"

        val blocks = ReadingBlocks.from(content)

        assertEquals(
            listOf(ReadingBlock.Heading("メモ")) + paragraphs("自分のメモ") +
                listOf(ReadingBlock.Heading("文字起こし")) + paragraphs("1行目", "2行目") +
                listOf(ReadingBlock.Heading("画像の内容")) + paragraphs("文字"),
            blocks
        )
    }

    @Test
    fun メモが無ければ取り出した区画だけに見出しを付ける() {
        val blocks = ReadingBlocks.from("--- 文字起こし ---\n話した内容")

        assertEquals(listOf(ReadingBlock.Heading("文字起こし")) + paragraphs("話した内容"), blocks)
    }

    @Test
    fun 読み進めた割合は先頭が0で最後が1で範囲に収まる() {
        assertEquals(0f, ReadingBlocks.progress(0, 10), 0f)
        assertEquals(1f, ReadingBlocks.progress(9, 10), 0f)
        assertEquals(0.5f, ReadingBlocks.progress(5, 11), 0f)
        assertEquals(1f, ReadingBlocks.progress(50, 10), 0f)
        // 区切りが1つ以下なら進みようがない
        assertEquals(0f, ReadingBlocks.progress(0, 1), 0f)
        assertEquals(0f, ReadingBlocks.progress(0, 0), 0f)
    }

    @Test
    fun 文字の大きさは1段階ずつ変わり端で止まる() {
        assertEquals(ReadingFontSize.LARGE, ReadingFontSize.MEDIUM.larger())
        assertEquals(ReadingFontSize.XLARGE, ReadingFontSize.XLARGE.larger())
        assertEquals(ReadingFontSize.SMALL, ReadingFontSize.MEDIUM.smaller())
        assertEquals(ReadingFontSize.SMALL, ReadingFontSize.SMALL.smaller())
    }
}

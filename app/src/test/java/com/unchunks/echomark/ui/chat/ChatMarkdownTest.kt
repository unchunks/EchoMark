package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.ui.chat.ChatMarkdown.Block
import com.unchunks.echomark.ui.chat.ChatMarkdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownTest {

    @Test
    fun 段落は空行で分かれ_段落内の改行は保つ() {
        val blocks = ChatMarkdown.parse("1行目\n2行目\n\n次の段落")

        assertEquals(
            listOf(
                Block.Paragraph(listOf(Span("1行目\n2行目"))),
                Block.Paragraph(listOf(Span("次の段落")))
            ),
            blocks
        )
    }

    @Test
    fun 見出しと箇条書きと番号付きリストを解釈する() {
        val blocks = ChatMarkdown.parse("# 大見出し\n### 小見出し\n- りんご\n* みかん\n  - 入れ子\n1. 最初\n2) 次")

        assertEquals(
            listOf(
                Block.Heading(1, listOf(Span("大見出し"))),
                Block.Heading(3, listOf(Span("小見出し"))),
                Block.ListItem("•", ordered = false, indent = 0, spans = listOf(Span("りんご"))),
                Block.ListItem("•", ordered = false, indent = 0, spans = listOf(Span("みかん"))),
                Block.ListItem("•", ordered = false, indent = 1, spans = listOf(Span("入れ子"))),
                Block.ListItem("1.", ordered = true, indent = 0, spans = listOf(Span("最初"))),
                Block.ListItem("2.", ordered = true, indent = 0, spans = listOf(Span("次")))
            ),
            blocks
        )
    }

    @Test
    fun コードブロックは中身をそのまま保ち_言語名を取り出す() {
        val blocks = ChatMarkdown.parse("前\n```kotlin\nval a = **1**\n  indent()\n```\n後")

        assertEquals(
            listOf(
                Block.Paragraph(listOf(Span("前"))),
                Block.Code("kotlin", "val a = **1**\n  indent()"),
                Block.Paragraph(listOf(Span("後")))
            ),
            blocks
        )
    }

    @Test
    fun 閉じていないコードブロックは最後までをコードにする() {
        assertEquals(listOf(Block.Code(null, "途中の\nコード")), ChatMarkdown.parse("```\n途中の\nコード"))
    }

    @Test
    fun 区切り線() {
        assertEquals(
            listOf(Block.Paragraph(listOf(Span("上"))), Block.Divider, Block.Paragraph(listOf(Span("下")))),
            ChatMarkdown.parse("上\n\n---\n\n下")
        )
    }

    @Test
    fun 太字と斜体とインラインコード() {
        assertEquals(
            listOf(
                Span("これは"),
                Span("太字", bold = true),
                Span("と"),
                Span("斜体", italic = true),
                Span("と"),
                Span("code()", code = true),
                Span("です")
            ),
            ChatMarkdown.parseInline("これは**太字**と*斜体*と`code()`です")
        )
    }

    @Test
    fun 生成途中で閉じていない記法は文字のまま出す() {
        assertEquals(listOf(Span("途中の**太字")), ChatMarkdown.parseInline("途中の**太字"))
        assertEquals(listOf(Span("バッククォート`だけ")), ChatMarkdown.parseInline("バッククォート`だけ"))
        assertEquals(listOf(Span("2 * 3 = 6")), ChatMarkdown.parseInline("2 * 3 = 6"))
    }

    @Test
    fun 引用番号は1つずつのSpanになる() {
        assertEquals(
            listOf(
                Span("根拠は"),
                Span("[1]", citation = 1),
                Span("と"),
                Span("[2]", citation = 2),
                Span("[3]", citation = 3),
                Span("です")
            ),
            ChatMarkdown.parseInline("根拠は[1]と[2, 3]です")
        )
        assertEquals(
            listOf(Span("A"), Span("[1]", citation = 1), Span("[2]", citation = 2)),
            ChatMarkdown.parseInline("A[1][2]")
        )
    }

    @Test
    fun 太字の中の引用番号は太字の情報を保つ() {
        assertEquals(
            listOf(Span("重要", bold = true), Span("[4]", bold = true, citation = 4)),
            ChatMarkdown.parseInline("**重要[4]**")
        )
    }

    @Test
    fun リンクは文字だけを残し_数字でない角括弧はそのまま() {
        assertEquals(
            listOf(Span("詳しくは公式サイトへ。[注]も参照")),
            ChatMarkdown.parseInline("詳しくは[公式サイト](https://example.com)へ。[注]も参照")
        )
    }

    @Test
    fun プレーンテキストへの変換() {
        assertEquals(
            "見出し\n本文 code\nval x = 1",
            ChatMarkdown.toPlainText("# 見出し\n本文 `code`\n```\nval x = 1\n```")
        )
        assertEquals(
            "要点\n• Compose は宣言的 [1]\n  • 入れ子",
            ChatMarkdown.toPlainText("## 要点\n- **Compose** は宣言的 [1]\n  - 入れ子")
        )
    }
}

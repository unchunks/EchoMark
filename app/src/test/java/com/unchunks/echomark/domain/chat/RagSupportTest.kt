package com.unchunks.echomark.domain.chat

import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RagSupportTest {

    @Test
    fun distanceToSimilarity_convertsCosineDistance() {
        assertEquals(1.0, RagSupport.distanceToSimilarity(0.0), 1e-9)
        assertEquals(0.3, RagSupport.distanceToSimilarity(0.7), 1e-9)
    }

    @Test
    fun selectRelevantIds_dropsLowSimilarityAndSortsByDistance() {
        // 距離 0.2 -> 類似度 0.8 / 0.5 -> 0.5(境界) / 0.6 -> 0.4(除外)
        val hits = listOf(3L to 0.5, 1L to 0.6, 2L to 0.2)
        assertEquals(listOf(2L, 3L), RagSupport.selectRelevantIds(hits, 0.5))
    }

    @Test
    fun selectRelevantIds_emptyWhenAllBelowThreshold() {
        assertEquals(emptyList<Long>(), RagSupport.selectRelevantIds(listOf(1L to 1.2), 0.5))
    }

    @Test
    fun selectRelevantIds_usesGivenThreshold() {
        // 距離 0.5(類似度 0.5)は、しきい値 0.6 のモデルでは除外され、0.4 のモデルでは残る
        assertEquals(emptyList<Long>(), RagSupport.selectRelevantIds(listOf(1L to 0.5), 0.6))
        assertEquals(listOf(1L), RagSupport.selectRelevantIds(listOf(1L to 0.5), 0.4))
    }

    @Test
    fun selectRelevantIds_removesDuplicates() {
        assertEquals(listOf(1L), RagSupport.selectRelevantIds(listOf(1L to 0.1, 1L to 0.2), 0.5))
    }

    @Test
    fun formatContextEntry_formatsAndNormalizesWhitespace() {
        assertEquals("[2] 記事: 本文 です", RagSupport.formatContextEntry(2, "記事", " 本文\n  です "))
        assertEquals("[1] 記事", RagSupport.formatContextEntry(1, "記事", null))
    }

    @Test
    fun formatContextEntry_truncatesBody() {
        val entry = RagSupport.formatContextEntry(1, "T", "a".repeat(1000))
        assertEquals("[1] T: ".length + RagSupport.MAX_SNIPPET_CHARS, entry.length)
    }

    @Test
    fun titleFrom_truncatesWithEllipsis() {
        assertEquals("短い質問", RagSupport.titleFrom("短い質問"))
        assertEquals("あ".repeat(30) + "…", RagSupport.titleFrom("あ".repeat(50)))
        assertEquals("a b", RagSupport.titleFrom("a\nb"))
    }

    @Test
    fun pinFirst_putsPinnedFirstWithoutDuplicatesAndLimits() {
        assertEquals(listOf(9L, 1L, 2L), RagSupport.pinFirst(9L, listOf(1L, 9L, 2L), idOf = { it }))
        assertEquals(listOf(9L, 1L), RagSupport.pinFirst(9L, listOf(1L, 2L, 3L), idOf = { it }, limit = 2))
        assertEquals(listOf(1L, 2L), RagSupport.pinFirst(null, listOf(1L, 1L, 2L), idOf = { it }))
    }

    @Test
    fun formatPinnedContextEntry_includesMarkSummaryAndLongerBody() {
        val body = "本".repeat(2000)
        val entry = RagSupport.formatPinnedContextEntry(1, "記事", "要約", body)

        assertTrue(entry.startsWith("[1] ${RagSupport.PINNED_MARK} 記事: 要約 本文: 本本"))
        assertEquals(RagSupport.PINNED_SNIPPET_CHARS, entry.substringAfter(": ").length)
        // 要約も本文も無ければタイトルだけ
        assertEquals("[2] ${RagSupport.PINNED_MARK} 記事", RagSupport.formatPinnedContextEntry(2, "記事", null, " "))
    }

    @Test
    fun searchQueryFor_addsPinnedTitle() {
        assertEquals("要約して", RagSupport.searchQueryFor("要約して", null))
        assertEquals("Compose 入門 要約して", RagSupport.searchQueryFor("要約して", "Compose 入門"))
    }

    @Test
    fun aboutBookmarkTitle_fitsWithinTitleLimit() {
        assertEquals("Compose 入門について", RagSupport.aboutBookmarkTitle("Compose 入門"))
        val long = RagSupport.aboutBookmarkTitle("あ".repeat(50))
        assertEquals("あ".repeat(RagSupport.TITLE_MAX_CHARS - 4) + "…について", long)
    }

    @Test
    fun tagsMentionedIn_matchesNamesInMessageIgnoringCaseAndShortNames() {
        val tags = listOf(Tag(1, "AI"), Tag(2, "技術"), Tag(3, "機械学習"), Tag(4, "本"), Tag(5, "料理"))

        assertEquals(
            listOf(1L, 2L, 3L),
            RagSupport.tagsMentionedIn("「機械学習」と「技術」とaiの保存をまとめて", tags).map { it.id }
        )
        assertEquals(emptyList<Long>(), RagSupport.tagsMentionedIn("最近保存した本は？", tags).map { it.id })
    }

    @Test
    fun contextTitle_showsDaysSinceSaved() {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day

        assertEquals("記事(今日保存)", RagSupport.contextTitle("記事", now - day / 2, now))
        assertEquals("記事(3日前に保存)", RagSupport.contextTitle("記事", now - 3 * day, now))
        assertEquals("記事(今日保存)", RagSupport.contextTitle("記事", now + day, now))
    }

    @Test
    fun 質問から英数字_カタカナ_漢字の語を取り出す() {
        assertEquals(
            listOf("Kotlin", "コルーチン", "例外処理"),
            RagSupport.keywordsOf("Kotlin のコルーチンの例外処理はどう書く？")
        )
        // 全角の英数字もそろえ、C# や Node.js のような記号入りの語も1語にする
        assertEquals(listOf("C#", "Node.js"), RagSupport.keywordsOf("Ｃ# と Node.js の違い"))
    }

    @Test
    fun 保存全体についての質問によく出る語は検索語にしない() {
        assertEquals(emptyList<String>(), RagSupport.keywordsOf("最近保存した記事の要点は？"))
    }

    @Test
    fun 続きの質問は前の質問と合わせて探す() {
        val history = listOf(
            ChatMessage(conversationId = 1, role = ChatRole.USER, content = "Compose の再コンポーズを減らす方法", createdAt = 1),
            ChatMessage(conversationId = 1, role = ChatRole.ASSISTANT, content = "remember を使います [1]", referencedBookmarkIds = listOf(7, 8), createdAt = 2)
        )

        assertEquals("Compose の再コンポーズを減らす方法 もっと詳しく", RagSupport.searchQueryWithHistory("もっと詳しく", history))
        assertEquals(listOf(7L, 8L), RagSupport.previousReferenceIds("それの例は？", history))
        // 続きでない質問はそのまま
        assertEquals(
            "Gradle のビルドを速くするにはどうすればいいですか",
            RagSupport.searchQueryWithHistory("Gradle のビルドを速くするにはどうすればいいですか", history)
        )
        assertEquals(emptyList<Long>(), RagSupport.previousReferenceIds("Gradle のビルドを速くするにはどうすればいいですか", history))
    }

    @Test
    fun 本文から質問の語が最も多く出てくる部分を取り出す() {
        val content = "あ".repeat(500) + "Gradle の configuration cache を有効にするとビルドが速くなる。" + "い".repeat(500)

        val passage = RagSupport.bestPassage(content, listOf("Gradle", "configuration"), maxChars = 100)!!

        assertTrue(passage, passage.contains("configuration cache"))
        assertTrue(passage.startsWith("…") && passage.endsWith("…"))
    }

    @Test
    fun 語が出てこない本文や短い本文からは部分を取り出さない() {
        assertNull(RagSupport.bestPassage("あ".repeat(500), listOf("Gradle")))
        assertNull(RagSupport.bestPassage("Gradle の話", listOf("Gradle")))
        assertNull(RagSupport.bestPassage("Gradle".repeat(100), emptyList()))
    }

    @Test
    fun 要約に関連する部分を添える() {
        assertEquals("要約 関連する部分: 「本文の一部」", RagSupport.contextBody("要約", "本文", "本文の一部"))
        assertEquals("要約", RagSupport.contextBody("要約", "本文", null))
        // 要約が無ければ本文
        assertEquals("本文", RagSupport.contextBody(null, "本文", null))
    }
}

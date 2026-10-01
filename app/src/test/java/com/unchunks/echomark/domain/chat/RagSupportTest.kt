package com.unchunks.echomark.domain.chat

import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import org.junit.Assert.assertEquals
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
        assertEquals(emptyList<Long>(), RagSupport.selectRelevantIds(listOf(1L to 1.2)))
    }

    @Test
    fun selectRelevantIds_removesDuplicates() {
        assertEquals(listOf(1L), RagSupport.selectRelevantIds(listOf(1L to 0.1, 1L to 0.2)))
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
    fun recentHistory_keepsLastNInOrder() {
        val msgs = (1..10).map {
            ChatMessage(
                id = it.toLong(), conversationId = 1, role = ChatRole.USER,
                content = "m$it", createdAt = it.toLong()
            )
        }
        assertEquals((5..10).map { it.toLong() }, RagSupport.recentHistory(msgs, 6).map { it.id })
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
}

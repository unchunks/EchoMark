package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 絞り込み条件の素のモデル(タグ・カテゴリの選び方)。 */
class BookmarkQueryTest {

    @Test
    fun 既定は通常の一覧でタグもカテゴリも絞らない() {
        val query = BookmarkQuery()

        assertEquals(BookmarkFilter.ACTIVE, query.filter)
        assertEquals(TagMatch.ALL, query.tagMatch)
        assertFalse(query.isNarrowed)
    }

    @Test
    fun タグを選ぶ_もう一度選ぶと外れる() {
        val query = BookmarkQuery().toggleTag(1).toggleTag(2)

        assertEquals(setOf(1L, 2L), query.tagIds)
        assertEquals(setOf(2L), query.toggleTag(1).tagIds)
        assertTrue(query.isNarrowed)
    }

    @Test
    fun カテゴリは大文字小文字を区別せず選び外しする() {
        val query = BookmarkQuery().toggleCategory("Tech")

        assertEquals(setOf("Tech"), query.categories)
        assertEquals(emptySet<String>(), query.toggleCategory("tech").categories)
        assertEquals(setOf("Tech", "旅行"), query.toggleCategory("旅行").categories)
    }

    @Test
    fun タグとカテゴリを外しても通常_お気に入り_アーカイブは変わらず_組み合わせ方は戻る() {
        val query = BookmarkQuery(
            filter = BookmarkFilter.FAVORITES, tagIds = setOf(1, 2), tagMatch = TagMatch.ANY, categories = setOf("技術")
        )

        val cleared = query.clearConditions()

        assertEquals(BookmarkQuery(filter = BookmarkFilter.FAVORITES), cleared)
        assertFalse(cleared.isNarrowed)
    }

    @Test
    fun 種類と期間を選ぶ_もう一度選ぶと外れ_選んでいるあいだは絞り込み中() {
        val query = BookmarkQuery().toggleType(BookmarkType.PDF).toggleType(BookmarkType.IMAGE)

        assertEquals(setOf(BookmarkType.PDF, BookmarkType.IMAGE), query.types)
        assertEquals(setOf(BookmarkType.IMAGE), query.toggleType(BookmarkType.PDF).types)
        assertTrue(query.isNarrowed)

        val withPeriod = BookmarkQuery().togglePeriod(CreatedPeriod.PAST_MONTH)
        assertEquals(CreatedPeriod.PAST_MONTH, withPeriod.period)
        assertTrue(withPeriod.isNarrowed)
        // 同じ期間をもう一度選ぶと外れ、別の期間を選ぶと置き換わる
        assertEquals(null, withPeriod.togglePeriod(CreatedPeriod.PAST_MONTH).period)
        assertEquals(CreatedPeriod.PAST_WEEK, withPeriod.togglePeriod(CreatedPeriod.PAST_WEEK).period)
    }

    @Test
    fun すべて解除は種類と期間も外す() {
        val query = BookmarkQuery(
            filter = BookmarkFilter.ARCHIVED, tagIds = setOf(1), types = setOf(BookmarkType.URL), period = CreatedPeriod.PAST_YEAR
        )

        assertEquals(BookmarkQuery(filter = BookmarkFilter.ARCHIVED), query.clearConditions())
    }

    @Test
    fun 期間の始まりは今日から日数をさかのぼった時刻() {
        val now = 1_000L * 24 * 60 * 60 * 1000
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, CreatedPeriod.PAST_WEEK.sinceMillis(now))
        assertEquals(now - 365L * 24 * 60 * 60 * 1000, CreatedPeriod.PAST_YEAR.sinceMillis(now))
    }
}

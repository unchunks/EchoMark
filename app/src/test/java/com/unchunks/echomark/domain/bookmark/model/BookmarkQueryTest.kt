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
        assertFalse(query.hasTagOrCategory)
    }

    @Test
    fun タグを選ぶ_もう一度選ぶと外れる() {
        val query = BookmarkQuery().toggleTag(1).toggleTag(2)

        assertEquals(setOf(1L, 2L), query.tagIds)
        assertEquals(setOf(2L), query.toggleTag(1).tagIds)
        assertTrue(query.hasTagOrCategory)
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

        val cleared = query.clearTagsAndCategories()

        assertEquals(BookmarkQuery(filter = BookmarkFilter.FAVORITES), cleared)
        assertFalse(cleared.hasTagOrCategory)
    }
}

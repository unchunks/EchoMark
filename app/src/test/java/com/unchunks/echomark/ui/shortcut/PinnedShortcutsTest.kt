package com.unchunks.echomark.ui.shortcut

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.ui.navigation.Routes
import com.unchunks.echomark.ui.shortcut.PinnedShortcuts.ellipsized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedShortcutsTest {

    private fun bookmark(id: Long, title: String) =
        Bookmark(id = id, type = BookmarkType.URL, title = title, createdAt = 0, lastAccessedAt = 0)

    @Test
    fun ブックマークのショートカットは_詳細へのディープリンクと名前を持つ() {
        val spec = PinnedShortcuts.forBookmark(bookmark(42, "短い題"))
        assertEquals("bookmark_42", spec.id)
        assertEquals("echomark://bookmark/42", spec.uri)
        assertEquals("短い題", spec.shortLabel)
        assertEquals("短い題", spec.longLabel)
    }

    @Test
    fun 長い題は_目安の長さで切って末尾を省略記号にする() {
        val title = "Jetpack Compose のパフォーマンスを改善する 7 つのヒント(完全版)"
        val spec = PinnedShortcuts.forBookmark(bookmark(1, title))
        assertEquals(PinnedShortcuts.SHORT_LABEL_MAX, spec.shortLabel.length)
        assertTrue(spec.shortLabel.endsWith("…"))
        assertEquals(PinnedShortcuts.LONG_LABEL_MAX, spec.longLabel.length)
        assertTrue(title.startsWith(spec.shortLabel.removeSuffix("…")))
    }

    @Test
    fun 題が空なら代わりの名前を使う() {
        assertEquals("ブックマーク", PinnedShortcuts.forBookmark(bookmark(1, "   ")).shortLabel)
        assertEquals("コレクション", PinnedShortcuts.forCollection(1, "").shortLabel)
    }

    @Test
    fun コレクションのショートカットは_コレクションへのディープリンクを持つ() {
        val spec = PinnedShortcuts.forCollection(7, "読みたい記事")
        assertEquals("collection_7", spec.id)
        assertEquals("echomark://collection/7", spec.uri)
        assertEquals("読みたい記事", spec.shortLabel)
        assertEquals("コレクション「読みたい記事」", spec.longLabel)
    }

    @Test
    fun コレクションの長い名前は_ラベルの長さに収まる() {
        val spec = PinnedShortcuts.forCollection(7, "あ".repeat(40))
        assertTrue(spec.shortLabel.length <= PinnedShortcuts.SHORT_LABEL_MAX)
        assertTrue(spec.longLabel.length <= PinnedShortcuts.LONG_LABEL_MAX + "コレクション「」".length)
    }

    @Test
    fun 省略は指定の長さに収まり_短ければそのまま() {
        assertEquals("abc", "abc".ellipsized(3))
        assertEquals("ab…", "abcd".ellipsized(3))
        assertEquals("…", "abcd".ellipsized(1))
    }

    @Test
    fun ディープリンクはルートの定義と同じ形() {
        assertEquals("echomark://collection/{collectionId}", Routes.COLLECTION_DEEP_LINK)
        assertEquals("echomark://collection/9", Routes.collectionDeepLink(9))
        // 通知・ウィジェットと同じ詳細のディープリンクを使う
        assertEquals("echomark://bookmark/9", Routes.bookmarkDeepLink(9))
    }
}

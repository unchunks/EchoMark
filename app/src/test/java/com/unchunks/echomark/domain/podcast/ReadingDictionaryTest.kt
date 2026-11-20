package com.unchunks.echomark.domain.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 読みの辞書の1件の確かめと、入れる・消す・読み込んだものを足す。 */
class ReadingDictionaryTest {

    @Test
    fun 誤りのある1件() {
        assertNull(ReadingEntry.problemOf("日本橋", "にほんばし"))
        assertNull(ReadingEntry.problemOf("GPT-4", "ジーピーティーフォー"))
        assertEquals("表記を入れてください", ReadingEntry.problemOf(" ", "よみ"))
        assertEquals("読みを入れてください", ReadingEntry.problemOf("表記", ""))
        assertEquals("読みが表記と同じです", ReadingEntry.problemOf("GitHub", "github"))
        assertNotNull(ReadingEntry.problemOf("あ".repeat(ReadingEntry.MAX_SURFACE_CHARS + 1), "よみ"))
        assertNotNull(ReadingEntry.problemOf("表記", "よ".repeat(ReadingEntry.MAX_READING_CHARS + 1)))
    }

    @Test
    fun かなだけか() {
        assertTrue(ReadingEntry.isKana("にほんばし"))
        assertTrue(ReadingEntry.isKana("ギットハブ・アクションズ"))
        assertFalse(ReadingEntry.isKana("日本橋"))
        assertFalse(ReadingEntry.isKana("nihonbashi"))
        assertFalse(ReadingEntry.isKana(" "))
    }

    @Test
    fun 同じ表記は置き換え_新しいものを先にする() {
        val current = listOf(ReadingEntry("東京", "とうきょう"), ReadingEntry("GitHub", "ぎっとはぶ"))
        val updated = ReadingDictionary.upsert(current, ReadingEntry("ｇｉｔｈｕｂ", "ギットハブ"))
        assertEquals(listOf(ReadingEntry("github", "ギットハブ"), ReadingEntry("東京", "とうきょう")), updated)
    }

    @Test
    fun 表記で消す() {
        val current = listOf(ReadingEntry("東京", "とうきょう"), ReadingEntry("GitHub", "ギットハブ"))
        assertEquals(listOf(ReadingEntry("東京", "とうきょう")), ReadingDictionary.remove(current, "GITHUB"))
    }

    @Test
    fun 読み込んだものは今の読みを残して足す() {
        val current = listOf(ReadingEntry("東京", "とうきょう"))
        val incoming = listOf(ReadingEntry("東京", "ひがしきょう"), ReadingEntry("大阪", "おおさか"), ReadingEntry("", "から"))
        assertEquals(
            listOf(ReadingEntry("東京", "とうきょう"), ReadingEntry("大阪", "おおさか")),
            ReadingDictionary.merge(current, incoming)
        )
    }

    @Test
    fun 上限を超えては入れない() {
        val full = (1..ReadingEntry.MAX_ENTRIES).map { ReadingEntry("語$it", "ご$it") }
        val updated = ReadingDictionary.upsert(full, ReadingEntry("新しい語", "あたらしいご"))
        assertEquals(ReadingEntry.MAX_ENTRIES, updated.size)
        assertEquals("新しい語", updated.first().surface)
    }
}
